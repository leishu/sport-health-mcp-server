package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.example.sporthealth.domain.EcgRecord;
import org.example.sporthealth.domain.Medal;
import org.example.sporthealth.domain.SampleSequence;
import org.example.sporthealth.repository.HuaweiRecordStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code HuaweiRecordTools} 的读路径：勋章、ECG 元数据、样本序列元数据。
 *
 * <p>用真实 SQLite 而不是 mock：这些表都是派生表/源表的直接查询，
 * 对缺省字段（null 处理）和分页/过滤语义的关键。
 */
class HuaweiRecordToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private HuaweiRecordTools tools;
    private NamedParameterJdbcTemplate insert;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("db").resolve("sport-health.db");
        Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);

        jdbc = JdbcClient.create(dataSource);
        new SqliteSchema(jdbc, dataSource).ensure();

        tools = new HuaweiRecordTools(new HuaweiRecordStore(jdbc));
        insert = new NamedParameterJdbcTemplate(dataSource);
        seed();
    }

    @Test
    void medalsSortedByTakeDateDescAndFilterable() {
        Map<String, Object> result = tools.getMedals(null, null, null);
        assertEquals("OK", result.get("status"));
        List<Medal> medals = (List<Medal>) result.get("rows");
        assertEquals(3, medals.size());
        assertEquals("2026-03-01", medals.get(0).takeDate().substring(0, 10));
        assertEquals("AAA", medals.get(0).medalType());
        assertEquals("2026-01-02", medals.get(1).takeDate().substring(0, 10));
        assertEquals("CCC", medals.get(1).medalType());
        assertEquals("2026-01-01", medals.get(2).takeDate().substring(0, 10));

        Map<String, Object> onlyCcc = tools.getMedals("CCC", null, null);
        assertEquals("OK", onlyCcc.get("status"));
        assertEquals(2, onlyCcc.get("total"));

        Map<String, Object> empty = tools.getMedals("XXX", null, null);
        assertEquals("OK", empty.get("status"));
        assertEquals(0, empty.get("total"));
        assertTrue(empty.get("hasMore") instanceof Boolean);
    }

    @Test
    void ecgRecordsOrderedByStartTimeDesc() {
        Map<String, Object> result = tools.getEcgRecords(null);
        assertEquals("OK", result.get("status"));
        List<EcgRecord> records = (List<EcgRecord>) result.get("rows");
        assertEquals(2, records.size());
        assertEquals("ecg-2", records.get(0).recordId());
        assertEquals("ecg-1", records.get(1).recordId());
        // 元数据字段透传
        assertEquals(Integer.valueOf(51), records.get(1).averageHeartRate());
        assertEquals(Integer.valueOf(15000), records.get(1).detailSampleCount());
        assertEquals(Integer.valueOf(301), records.get(1).extendSampleCount());
    }

    @Test
    void sampleSequencesFilterableByTypeAndRangeWithTotal() {
        Map<String, Object> all = tools.getSampleSequences(null, null, null, null);
        assertEquals("OK", all.get("status"));
        List<SampleSequence> rows = (List<SampleSequence>) all.get("rows");
        assertEquals(5, rows.size());
        assertEquals(5L, ((Number) all.get("totalInRange")).longValue());
        // 按 type_code 过滤：只有 700021 是 2 条
        Map<String, Object> hrv = tools.getSampleSequences("700021", null, null, null);
        assertEquals("OK", hrv.get("status"));
        List<SampleSequence> hrvRows = (List<SampleSequence>) hrv.get("rows");
        assertEquals(2, hrvRows.size());
        // 日期区间过滤：ecg-0001 于 2026-06-12，在 [2026-06-12, 2026-06-13) 内
        Map<String, Object> range = tools.getSampleSequences("700009", "2026-06-12", "2026-06-13", null);
        assertEquals("OK", range.get("status"));
        List<SampleSequence> rangeRows = (List<SampleSequence>) range.get("rows");
        assertEquals(1, rangeRows.size());
        assertEquals("ecg-0001", rangeRows.get(0).recordId());
        assertEquals(1L, ((Number) range.get("totalInRange")).longValue());
    }

    @Test
    void sampleSequencesNoDataWhenTypeAbsent() {
        Map<String, Object> none = tools.getSampleSequences("700014", null, null, null);
        assertEquals("NO_DATA", none.get("status"));
    }

    @Test
    void paginationReportsHasMoreWhenLimitCutsRows() {
        Map<String, Object> result = tools.getSampleSequences(null, null, null, 2);
        assertEquals("OK", result.get("status"));
        assertEquals(2, result.get("total"));
        assertTrue((Boolean) result.get("hasMore"));
    }

    private void seed() {
        // ---- medals（PK 为 medal_type + medal_level + excel_row，每行 excel_row 需唯一）
        insertMedal(1, "CCC", "0", 3, "2026-01-01T00:00:00.000Z", "2026-01-01T01:00:00.000Z");
        insertMedal(2, "CCC", "0", 1, "2026-01-02T00:00:00.000Z", "2026-01-02T01:00:00.000Z");
        insertMedal(3, "AAA", "5", 2, "2026-03-01T00:00:00.000Z", "2026-03-01T01:00:00.000Z");
        // ---- ecg_record
        insertEcg("ecg-1", LocalDateTime.of(2026, 6, 12, 14, 8, 15), 51, "HUAWEI");
        insertEcg("ecg-2", LocalDateTime.of(2026, 6, 12, 15, 0, 0), 52, "HUAWEI");
        // ---- sample_sequence: 5 条（700021 x2, 700009 x1, 700008 x1, 700013 x1）
        insertSample("rec-001", 700021, LocalDateTime.of(2026, 6, 13, 22, 16, 0), 47);
        insertSample("rec-002", 700021, LocalDateTime.of(2026, 6, 13, 23, 6, 0), 48);
        insertSample("ecg-0001", 700009, LocalDateTime.of(2026, 6, 12, 14, 8, 15), 51);
        insertSample("frag-001", 700008, LocalDateTime.of(2026, 6, 13, 1, 30, 0), 42);
        insertSample("report-001", 700013, LocalDateTime.of(2026, 6, 12, 10, 30, 0), 80);
    }

    private void insertMedal(int row, String type, String level, Integer count, String takeDate, String modifyTime) {
        insert.update("INSERT INTO medal (medal_type, medal_level, excel_row, gain_count, take_date, modify_time)"
                + " VALUES (:t, :l, :r, :c, :td, :mt)",
                new MapSqlParameterSource("t", type)
                        .addValue("l", level)
                        .addValue("r", row)
                        .addValue("c", count)
                        .addValue("td", takeDate)
                        .addValue("mt", modifyTime));
    }

    private void insertEcg(String recordId, LocalDateTime start, Integer heartRate, String source) {
        insert.update("INSERT INTO ecg_record (record_id, start_time, end_time, average_heart_rate,"
                + " ecg_arrhy_type, ecg_data_length, data_sources, ecg_app_version, package_name,"
                + " user_symptom, device_code, detail_sample_count, extend_sample_count)"
                + " VALUES (:id, :start, :end, :hr, :arrh, :len, :src, :app, :pkg, :sym, :dev,"
                + " :detail, :extend)",
                new MapSqlParameterSource("id", recordId)
                        .addValue("start", start.toString())
                        .addValue("end", start.plusSeconds(60).toString())
                        .addValue("hr", heartRate)
                        .addValue("arrh", 1)
                        .addValue("len", 15000)
                        .addValue("src", source)
                        .addValue("app", "1.0.0")
                        .addValue("pkg", "com.huawei.health")
                        .addValue("sym", 0)
                        .addValue("dev", null)
                        .addValue("detail", 15000)
                        .addValue("extend", 301));
    }

    private void insertSample(String recordId, int type, LocalDateTime start, Integer avg) {
        insert.update("INSERT INTO sample_sequence (record_id, type_code, data_id, sub_type,"
                + " start_time, end_time, time_zone, device_code, summary_data, detail_data, extend_data,"
                + " source_file) VALUES (:id, :type, :dataId, :subType, :start, :end, :tz, :dev, NULL, NULL,"
                + " NULL, 'seed.json')",
                new MapSqlParameterSource("id", recordId)
                        .addValue("type", type)
                        .addValue("dataId", (int) Math.round(Math.random() * 1000) + 1)
                        .addValue("subType", 0)
                        .addValue("start", start.toString())
                        .addValue("end", start.plusSeconds(30).toString())
                        .addValue("tz", "+0800")
                        .addValue("dev", null));
    }
}
