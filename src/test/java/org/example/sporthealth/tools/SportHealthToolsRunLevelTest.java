package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.OfficialRaceComparison;
import org.example.sporthealth.domain.RunLevel;
import org.example.sporthealth.domain.RunLevelHistory;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.RunLevelStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.service.RunLevelService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 官方跑力在工具层的暴露方式：{@code get_run_level} 的取值与趋势，
 * 以及 {@code predict_race_time(includeOfficial=true)} 的「两组预测 + 来源标注」结构。
 *
 * <p>跑步数据用 {@code classpath:tcx-dataset}（2026-10-25 ~ 2026-11-03），
 * 官方跑力用真实 SQLite 表，两者拼在一起才能验证「按区间结束日取最近快照」。
 */
class SportHealthToolsRunLevelTest {

    @TempDir
    Path tempDir;

    private SportHealthTools tools;
    private JdbcClient jdbc;

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

        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
        RunLevelService runLevelService = new RunLevelService(new RunLevelStore(jdbc));
        tools = new SportHealthTools(analytics, repository, properties, null, runLevelService);

        level("20261031", 44.0, 13000);
        level("20261103", 46.0, 12926);
    }

    @Test
    void returnsLatestRunLevelWithZonesAndOfficialTimes() {
        Map<String, Object> result = tools.getRunLevel(null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("2026-11-03", result.get("date").toString());
        assertEquals("HUAWEI_OFFICIAL", result.get("source"));
        assertEquals(46.0, (Double) result.get("runLevel"), 1e-9);
        assertEquals(5, zones(result).size());
        assertEquals(6, officialTimes(result).size());
        String text = (String) result.get("text");
        assertTrue(text.contains("华为官方跑力 46.0（2026-11-03）"), text);
        assertTrue(text.contains("全程马拉松 3:35:26"), text);
    }

    @Test
    void fallsBackToEarlierSnapshotAndExplainsWhy() {
        Map<String, Object> result = tools.getRunLevel("2026-11-01", null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("2026-10-31", result.get("date").toString());
        assertEquals("2026-11-01", result.get("requestedDate").toString());
        assertTrue(((String) result.get("note")).contains("已取之前最近一天"), (String) result.get("note"));
    }

    @Test
    void appendsHistoryWhenRequested() {
        Map<String, Object> result = tools.getRunLevel(null, true, 30);

        RunLevelHistory history = (RunLevelHistory) result.get("history");
        assertNotNull(history);
        assertEquals(2, history.days());
        assertEquals(2.0, history.scoreChange(), 1e-9);
        assertEquals(LocalDate.of(2026, 10, 31), history.points().get(0).date());
    }

    @Test
    void returnsNoDataWhenRequestedDatePrecedesAllSnapshots() {
        Map<String, Object> result = tools.getRunLevel("2026-01-01", null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertTrue(((String) result.get("message")).contains("没有找到"), (String) result.get("message"));
    }

    @Test
    void reportsNoDataWithoutStore() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
        SportHealthTools withoutStore = new SportHealthTools(analytics, repository, properties, null);

        Map<String, Object> result = withoutStore.getRunLevel(null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("HUAWEI_OFFICIAL", result.get("source"));
    }

    @Test
    void predictRaceTimeReturnsBothGroupsWithSources() {
        Map<String, Object> result = tools.predictRaceTime(null, null, null, true);

        assertEquals("OK", result.get("status"));
        // 顶层仍是 Riegel，保持老字段不变
        assertEquals("riegel", result.get("method"));
        assertNotNull(result.get("predictions"));

        @SuppressWarnings("unchecked")
        Map<String, Object> official = (Map<String, Object>) result.get("official");
        assertEquals("OK", official.get("status"));
        assertEquals("HUAWEI_OFFICIAL", official.get("source"));
        assertEquals("2026-11-03", official.get("date").toString());
        assertEquals(46.0, (Double) official.get("runLevel"), 1e-9);
        assertTrue(Boolean.TRUE.equals(official.get("withinRange")));
        assertEquals(0L, ((Number) official.get("staleDays")).longValue());

        List<?> rows = (List<?>) official.get("predictions");
        assertEquals(4, rows.size(), "默认四档都有官方成绩");
        OfficialRaceComparison.Row marathon = (OfficialRaceComparison.Row) rows.get(3);
        assertEquals("HUAWEI_OFFICIAL", marathon.officialSource());
        assertNotNull(marathon.riegelSource());
        assertNotNull(marathon.officialText());
        assertNotNull(marathon.riegelText());
        assertNotNull(marathon.deltaText());
        assertNotNull(marathon.deltaSeconds());
        assertTrue(((String) official.get("text")).startsWith("华为官方跑力 46.0"));
    }

    @Test
    void predictRaceTimeMarksDistanceWithoutOfficialCounterpart() {
        Map<String, Object> result = tools.predictRaceTime(null, null, 12d, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> official = (Map<String, Object>) result.get("official");
        assertEquals("OK", official.get("status"));
        assertTrue(((List<?>) official.get("predictions")).isEmpty());
        assertEquals(List.of(12.0), official.get("unavailableTargetsKm"));
        assertTrue(((String) official.get("text")).contains("官方只给"), (String) official.get("text"));
    }

    @Test
    void predictRaceTimeKeepsRiegelWhenOfficialMissing() {
        Map<String, Object> result = tools.predictRaceTime("2026-10-25", "2026-10-28", null, true);

        assertEquals("OK", result.get("status"), "Riegel 结果不受官方数据影响");
        @SuppressWarnings("unchecked")
        Map<String, Object> official = (Map<String, Object>) result.get("official");
        assertEquals("NO_DATA", official.get("status"));
        assertEquals("HUAWEI_OFFICIAL", official.get("source"));
        assertFalse(official.containsKey("predictions"));
    }

    @SuppressWarnings("unchecked")
    private static List<RunLevel.Zone> zones(Map<String, Object> result) {
        return (List<RunLevel.Zone>) result.get("zones");
    }

    @SuppressWarnings("unchecked")
    private static List<RunLevel.OfficialTime> officialTimes(Map<String, Object> result) {
        return (List<RunLevel.OfficialTime>) result.get("officialTimes");
    }

    private void level(String day, double score, double marathonSeconds) {
        jdbc.sql("""
                INSERT INTO run_level (
                    record_day, excel_row, current_run_level, a_speed, e_speed, i_speed, m_speed, r_speed,
                    km1_seconds, km3_seconds, km5_seconds, km10_seconds,
                    half_marathon_seconds, marathon_seconds
                ) VALUES (:day, 1, :score, 13.0716, 10.1094, 14.1015, 11.3048, 15.3722,
                          416, 801, 1367, 2806, 6133, :marathon)
                """)
                .param("day", day)
                .param("score", score)
                .param("marathon", marathonSeconds)
                .update();
    }
}
