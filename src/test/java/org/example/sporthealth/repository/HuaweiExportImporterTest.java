package org.example.sporthealth.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.importer.SourceFileScanner;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.importer.huawei.HealthDetailParser;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner;
import org.example.sporthealth.importer.huawei.ExcelWorkbookParser;
import org.example.sporthealth.importer.huawei.MinuteActivityParser;
import org.example.sporthealth.importer.huawei.MotionPathParser;
import org.example.sporthealth.importer.huawei.SampleSequenceParser;
import org.example.sporthealth.weather.OpenMeteoClient;
import org.example.sporthealth.weather.WeatherBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 华为 JSON 导出的导入语义：接管重合的 TCX 记录、按 recordId 去重、重复同步不重导。
 *
 * <p>用真实 SQLite + 真实 JSON 文本，而不是 mock：这里要验证的正是主键冲突、
 * 批量插入和增量指纹这些数据库层面的行为。
 */
class HuaweiExportImporterTest {

    private static final String SAMPLE_TCX = "tcx-dataset/20261025户外跑步.tcx";

    /** TCX 那次跑步的开始时刻，JSON 侧用 4 秒后的时间模拟「同一次跑步」。 */
    private static final Instant TCX_START = Instant.parse("2026-10-24T22:56:16.433Z");

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path dbFile;
    private HealthDataProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("health-data"));
        dbFile = tempDir.resolve("db").resolve("sport-health.db");
        copyTcx();
        write("HUAWEI_HEALTH/Motion path detail data & description/motion path detail data1_0.json",
                motionJson());
        write("HUAWEI_HEALTH/Health detail data & description/health detail data1_0.json", healthJson());
        write("HUAWEI_HEALTH/Sport per minute merged data & description/sport per minute merged data.json",
                minuteJson());
        write("HUAWEI_HEALTH/Sample sequence data & description/sample sequence data1_0.json",
                sampleJson());
        writeWorkbook("HUAWEI_HEALTH/SportsHealth data & desciption/user medal info.xls");
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @Test
    void jsonTakesOverTcxRunWithSameStartTime() {
        JdbcHealthDataRepository repository = repository();
        RunDataset dataset = repository.dataset();

        assertEquals(2, dataset.runCount(), "TCX 那次由 JSON 接管：接管后的 101 与 103 是跑步，102 是步行不计入");
        assertTrue(dataset.sourceFiles().stream().noneMatch(name -> name.endsWith(".tcx")),
                "接管后 TCX 行与它的来源文件都不该残留: " + dataset.sourceFiles());

        HuaweiExportImporter.StorageStats stats = repository.healthStats();
        assertEquals(3, stats.workouts(), "JSON 里的三条运动全部入库，接管的那次不再算第二行");
        assertEquals(2, stats.runningWorkouts(), "dataId 101/103 是跑步，102 是步行");
        assertEquals(1, stats.otherWorkouts());
        assertEquals(1, stats.healthMetrics(), "同一条记录的两个副本只能留一行");
        assertEquals(2, stats.activityMinutes());
        assertEquals(1, repository.lastHuaweiSync().mergedDuplicates());
    }

    /** 接管后留下的是 JSON 那份：心率、步数、热量都在，TCX 的 id 与轨迹一起消失。 */
    @Test
    void mergedRunKeepsJsonMetricsAndDropsTcxId() {
        RunDataset dataset = repository().dataset();

        assertTrue(dataset.activities().stream().noneMatch(run -> "20261025户外跑步".equals(run.id())),
                "TCX 的 id 不能与 JSON 记录并存");
        Map<String, Object> merged = jdbc().sql("""
                        SELECT total_calories_kcal, total_steps, avg_heart_rate, source_file
                        FROM run_activity WHERE id LIKE '%-101'
                        """)
                .query().singleRow();
        assertEquals(516.0, ((Number) merged.get("total_calories_kcal")).doubleValue(), 0.001);
        assertEquals(7300, ((Number) merged.get("total_steps")).intValue());
        assertEquals(130, ((Number) merged.get("avg_heart_rate")).intValue());
        assertTrue(((String) merged.get("source_file")).endsWith("motion path detail data1_0.json"),
                "来源改为 JSON 文件: " + merged.get("source_file"));

        assertEquals(0, jdbc().sql("SELECT COUNT(*) FROM track_point WHERE run_id = '20261025户外跑步'")
                .query(Integer.class).single(), "TCX 轨迹随接管一起删除");
    }

    @Test
    void keepsHeartRateAndCadenceOnJsonTrackPoints() {
        JdbcHealthDataRepository repository = repository();
        String runId = repository.dataset().activities().stream()
                .filter(activity -> activity.id().endsWith("-101"))
                .findFirst()
                .orElseThrow()
                .id();

        List<TrackPoint> points = repository.route(runId);

        assertEquals(1, points.size(), "哨兵点（90/-80）不入库");
        assertEquals(128, points.get(0).heartRate());
        assertEquals(180, points.get(0).cadence());
        assertTrue(points.get(0).latitude() < 39.905708, "落库坐标应为 WGS-84");
    }

    @Test
    void secondSyncSkipsUnchangedFiles() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        repository.reload();

        HuaweiExportImporter.Result sync = repository.lastHuaweiSync();
        assertEquals(5, sync.scanned(), "运动 / 健康明细 / 分钟数据 / 样本序列 / Excel 各一个文件");
        assertEquals(0, sync.imported(), "指纹命中后不应重新解析");
        assertEquals(5, sync.unchanged());
        assertEquals(2, repository.dataset().runCount(), "接管后仍是 2 次跑步，没有多出一行");
    }

    /**
     * 官方字段说明：{@code totalCalories} 单位是 cal、{@code mTotalDescent} 单位是分米，
     * 落库前必须换算，否则热量和下降都会大 10 倍。
     */
    @Test
    void convertsCaloriesAndDescentToMetricUnits() {
        repository().dataset();
        JdbcClient jdbc = jdbc();

        Map<String, Object> run = jdbc.sql("""
                        SELECT total_calories_kcal, descent_meters FROM run_activity WHERE id LIKE '%-103'
                        """)
                .query().singleRow();
        assertEquals(592.0, ((Number) run.get("total_calories_kcal")).doubleValue(), 0.001,
                "592000 cal = 592 kcal");
        assertEquals(0.0, ((Number) run.get("descent_meters")).doubleValue(), 0.001,
                "没给 mTotalDescent 时退回轨迹累加，只有一个点所以是 0");

        Map<String, Object> walk = jdbc.sql("""
                        SELECT total_calories_kcal, descent_meters FROM run_activity WHERE id LIKE '%-102'
                        """)
                .query().singleRow();
        assertEquals(90.0, ((Number) walk.get("total_calories_kcal")).doubleValue(), 0.001,
                "90000 cal = 90 kcal");
        assertEquals(13.5, ((Number) walk.get("descent_meters")).doubleValue(), 0.001,
                "mTotalDescent 是分米，135 应落成 13.5 米");

        double minuteKcal = jdbc.sql("SELECT SUM(calories_kcal) FROM activity_minute")
                .query(Double.class).single();
        assertEquals(13.07, minuteKcal, 0.001, "4070 + 9000 cal = 13.07 kcal");
    }

    /**
     * 原始长表写完之后，四张派生表要跟着补齐：心率按天汇总、分钟活动按天汇总。
     * 这两张是「心率 / 走路」最常被查的样子，空了就等于没导进来。
     */
    @Test
    void rebuildsDerivedTablesAfterImport() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        // 健康明细的时间是 2026-11-10T23:05Z，按 Asia/Shanghai 落在 11-11 07:05
        Map<String, Object> vitals = jdbc().sql(
                        "SELECT * FROM daily_vitals WHERE heart_date = '2026-11-11'")
                .query().singleRow();
        assertEquals(61.0, ((Number) vitals.get("avg_heart_rate")).doubleValue(), 0.001);
        assertEquals(61, ((Number) vitals.get("min_heart_rate")).intValue());
        assertEquals(1, ((Number) vitals.get("heart_rate_samples")).intValue(),
                "同一批数据的两份副本按主键去重后只剩一条");

        Map<String, Object> day = jdbc().sql(
                        "SELECT * FROM daily_activity WHERE activity_date = '2026-11-11'")
                .query().singleRow();
        assertEquals(273, ((Number) day.get("steps")).intValue());
        assertEquals(252.0, ((Number) day.get("distance_meters")).doubleValue(), 0.001);
        assertEquals(13.1, ((Number) day.get("calories_kcal")).doubleValue(), 0.001,
                "4.07 + 9.0 kcal，派生表统一保留一位小数");

        HuaweiExportImporter.StorageStats stats = repository.healthStats();
        assertEquals(1, stats.vitalDays());
        assertEquals(1, stats.activityDays());
        assertEquals(0, stats.bodyMeasurements(), "这份夹具里没有体重数据");
        assertEquals(0, stats.sleepSessions(), "这份夹具里没有睡眠分期");
    }

    /**
     * v8 新收的三类数据：样本序列、Excel 原始行、轨迹附加流。
     * 同时验证 sleep_report / ecg_record / medal / daily_sport_stat 四张派生表确实被回填。
     */
    @Test
    void importsSampleSequencesExcelRowsAndTrackStreams() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        HuaweiExportImporter.StorageStats stats = repository.healthStats();
        assertEquals(2, stats.sampleSequences());
        assertEquals(1, stats.sleepReports());
        assertEquals(1, stats.ecgRecords());
        assertEquals(2, stats.excelSheets());
        assertEquals(4, stats.excelRows());
        assertEquals(2, stats.trackStreams());

        Map<String, Object> sleep = jdbc().sql(
                        "SELECT * FROM sleep_report WHERE record_id = 'SLEEP-1'")
                .query().singleRow();
        assertEquals(75.0, ((Number) sleep.get("sleep_score")).doubleValue(), 0.001);
        assertEquals("2026-11-11", sleep.get("sleep_date"));

        Map<String, Object> ecg = jdbc().sql(
                        "SELECT * FROM ecg_record WHERE record_id = 'ECG-1'")
                .query().singleRow();
        assertEquals(51, ((Number) ecg.get("average_heart_rate")).intValue());
        assertEquals(3, ((Number) ecg.get("detail_sample_count")).intValue());
        assertEquals(1, ((Number) ecg.get("extend_sample_count")).intValue());

        assertEquals(2, jdbc().sql("SELECT COUNT(*) FROM medal").query(Integer.class).single());
        assertEquals(2, jdbc().sql("SELECT COUNT(*) FROM daily_sport_stat").query(Integer.class).single());
        Map<String, Object> day = jdbc().sql(
                        "SELECT * FROM daily_sport_stat WHERE activity_date = '2026-11-10'")
                .query().singleRow();
        assertEquals("+0800", day.get("time_zone"));

        assertEquals("rp",
                jdbc().sql("SELECT stream_type FROM track_stream WHERE stream_type = 'rp' LIMIT 1")
                        .query(String.class).single());
        String summary = jdbc().sql("SELECT summary_data FROM run_activity WHERE id LIKE '%-101'")
                .query(String.class).single();
        assertTrue(summary.contains("bestPace"), "summaryData 原文应完整保留: " + summary);
    }

    @Test
    void reimportKeepsRowCountStable() throws IOException {        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        long metricsAfterFirst = repository.healthStats().healthMetrics();

        // 改内容再同步：同一个文件重导，行数不能翻倍
        write("HUAWEI_HEALTH/Health detail data & description/health detail data1_0.json",
                healthJson().replace("\"61.0\"", "\"64.25\""));
        repository.reload();

        HuaweiExportImporter.StorageStats stats = repository.healthStats();
        assertEquals(metricsAfterFirst, stats.healthMetrics());
        assertEquals(1, repository.lastHuaweiSync().imported());
    }

    // ---------------------------------------------------------------- fixtures

    private String motionJson() {
        long duplicateStart = TCX_START.plusSeconds(4).toEpochMilli();
        long newStart = Instant.parse("2026-11-10T23:00:00Z").toEpochMilli();
        return """
                [
                  {"appType":1,"dataId":101,"type":30001,"subType":4,"deviceCode":1,
                   "startTime":%d,"endTime":%d,"timeZone":"+0800",
                   "summaryData":"{\\"sportType\\":258,\\"totalDistance\\":7123,\\"totalTime\\":2448000,\\"totalCalories\\":516000,\\"totalSteps\\":7300,\\"avgHeartRate\\":130,\\"maxHeartRate\\":152,\\"minHeartRate\\":101,\\"avgStepRate\\":181,\\"bestPace\\":338.5,\\"mTotalDescent\\":13.5}",
                   "detailData":"tp=lbs;k=0;lat=39.905708;lon=116.236651;alt=54.5;t=%d;\\ntp=h-r;k=%d;v=128;\\ntp=s-r;k=%d;v=180;\\ntp=alt;k=%d;v=58.8;\\ntp=lbs;k=1;lat=90.0;lon=-80.0;alt=0.0;t=%d;\\ntp=rs;k=5;v=3.2;\\ntp=rp;k=16;gct=283;sa=-1;\\n"},
                  {"appType":1,"dataId":102,"type":30001,"subType":5,"deviceCode":1,
                   "startTime":%d,"endTime":%d,"timeZone":"+0800",
                   "summaryData":"{\\"sportType\\":257,\\"totalDistance\\":2000,\\"totalTime\\":1800000,\\"totalCalories\\":90000,\\"totalSteps\\":2500,\\"avgHeartRate\\":95,\\"avgStepRate\\":110,\\"mTotalDescent\\":135}",
                   "detailData":"tp=lbs;k=0;lat=39.906000;lon=116.237000;alt=50.0;t=%d;\\n"},
                  {"appType":1,"dataId":103,"type":30001,"subType":4,"deviceCode":1,
                   "startTime":%d,"endTime":%d,"timeZone":"+0800",
                   "summaryData":"{\\"sportType\\":258,\\"totalDistance\\":8292,\\"totalTime\\":2890681,\\"totalCalories\\":592000,\\"totalSteps\\":8100,\\"avgHeartRate\\":128,\\"maxHeartRate\\":149,\\"minHeartRate\\":99,\\"avgStepRate\\":179}",
                   "detailData":"tp=lbs;k=0;lat=39.905708;lon=116.236651;alt=54.5;t=%d;\\ntp=h-r;k=%d;v=128;\\ntp=s-r;k=%d;v=180;\\ntp=alt;k=%d;v=58.8;\\ntp=lbs;k=1;lat=90.0;lon=-80.0;alt=0.0;t=%d;\\n"}
                ]
                """.formatted(duplicateStart, duplicateStart + 2448000, duplicateStart - 1000,
                duplicateStart, duplicateStart, duplicateStart - 1000, duplicateStart + 4000,
                newStart, newStart + 1800000, newStart,
                newStart + 60_000, newStart + 60_000 + 2890681, newStart + 60_000,
                newStart + 59_000, newStart + 59_000, newStart + 59_000, newStart + 120_000);
    }

    private String healthJson() {
        long time = Instant.parse("2026-11-10T23:05:00Z").toEpochMilli();
        return """
                [
                  {"recordId":"REC-1","type":7,"deviceCode":1,"timeZone":"+0800","startTime":%d,"endTime":%d,
                   "samplePoints":[{"key":"DATA_POINT_DYNAMIC_HEARTRATE","startTime":%d,"endTime":%d,"unit":"0","value":"61.0"}]},
                  {"recordId":"REC-1","type":500023,"deviceCode":2,"timeZone":"+0800","startTime":%d,"endTime":%d,
                   "samplePoints":[{"key":"DYNAMIC_HEART_RATE","startTime":%d,"endTime":%d,"unit":"0","value":"{\\"bpm\\":61}"}]}
                ]
                """.formatted(time, time, time, time, time, time, time, time);
    }

    private String minuteJson() {
        long start = Instant.parse("2026-11-10T23:05:00Z").toEpochMilli();
        return """
                [
                  {"recordDay":20261111,"timeZone":"+0800","version":1,
                   "sportDataUserData":[
                     {"dataId":1,"startTime":%d,"endTime":%d,"sportType":5,"deviceCode":1,
                      "sportBasicInfos":[{"steps":105,"distance":72,"calorie":4070,"altitude":0.0,"duration":1,"isIntensity":1}]},
                     {"dataId":2,"startTime":%d,"endTime":%d,"sportType":4,"deviceCode":1,
                      "sportBasicInfos":[{"steps":168,"distance":180,"calorie":9000,"altitude":0.0,"duration":1,"isIntensity":1}]}
                   ]}
                ]
                """.formatted(start, start + 60_000, start + 60_000, start + 120_000);
    }

    private String sampleJson() {
        long sleepStart = Instant.parse("2026-11-10T14:32:00Z").toEpochMilli();
        long sleepEnd = Instant.parse("2026-11-11T01:07:00Z").toEpochMilli();
        long ecgStart = Instant.parse("2026-11-10T02:08:15Z").toEpochMilli();
        return """
                [
                  {"appType":1,"dataId":281,"type":700013,"subType":0,"deviceCode":2374625270,
                   "recordId":"SLEEP-1","startTime":%d,"endTime":%d,"timeZone":"+0800",
                   "summaryData":"{\\"sleepScore\\":75,\\"sleepEfficiency\\":92,\\"avgBreathrate\\":14,\\"avgHeartrate\\":46,\\"avgHrv\\":49,\\"avgOxygenSaturation\\":97,\\"rdi\\":0,\\"snoreFreq\\":1,\\"wakeCount\\":0,\\"turnOverCount\\":0,\\"deepPart\\":73,\\"bedTime\\":1781274360,\\"fallAsleepTime\\":1781274720,\\"risingTime\\":1781299620,\\"wakeupTime\\":1781299620}",
                   "detailData":"6801000004000000"},
                  {"appType":1,"dataId":232,"type":700009,"subType":0,"deviceCode":2374625270,
                   "recordId":"ECG-1","startTime":%d,"endTime":%d,"timeZone":"+0800",
                   "summaryData":"{\\"averageHeartRate\\":51,\\"ecgArrhyType\\":1,\\"ecgDataLength\\":3,\\"dataSources\\":\\"HUAWEI\\",\\"ecgAppVersion\\":\\"V1.0.0.26\\",\\"packageName\\":\\"hw.health.ecganalysis\\",\\"userSymptom\\":0}",
                   "detailData":"[1.0,2.0,3.0]","extendData":"[4.0]"}
                ]
                """.formatted(sleepStart, sleepEnd, ecgStart, ecgStart + 60_000);
    }

    /** 用 POI 现造一个 .xls：两张有数据的工作表，覆盖勋章与每日运动统计。 */
    private void writeWorkbook(String relative) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet medal = workbook.createSheet("user medal info");
            header(medal, "medalType", "medalLevel", "gainCount", "takeDate", "modifyTime");
            medalRow(medal, 1, "A", "5", 3, "\"\t2025-01-11T08:06:59.000Z\"",
                    "\"\t2025-12-21T00:28:29.000Z\"");
            medalRow(medal, 2, "A", "5", 1, "\"\t2026-01-11T08:06:59.000Z\"",
                    "\"\t2026-12-21T00:28:29.000Z\"");

            Sheet sport = workbook.createSheet("user sport daily statistics");
            header(sport, "recordDay", "timeZone", "deviceCode", "steps");
            Row first = sport.createRow(1);
            first.createCell(0).setCellValue("2026-11-10");
            first.createCell(1).setCellValue("\"\t+0800\"");
            first.createCell(2).setCellValue("0");
            Row second = sport.createRow(2);
            second.createCell(0).setCellValue("2026-11-11");
            second.createCell(1).setCellValue("\"\t+0800\"");
            second.createCell(2).setCellValue("0");

            workbook.write(out);
            Path target = dataDir.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, out.toByteArray());
        }
    }

    private static void header(Sheet sheet, String... names) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < names.length; index++) {
            row.createCell(index).setCellValue(names[index]);
        }
    }

    private static void medalRow(Sheet sheet, int rowIndex, String type, String level,
                                 int count, String takeDate, String modifyTime) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(type);
        row.createCell(1).setCellValue(level);
        row.createCell(2).setCellValue(count);
        row.createCell(3).setCellValue(takeDate);
        row.createCell(4).setCellValue(modifyTime);
    }

    // ---------------------------------------------------------------- wiring

    private JdbcHealthDataRepository repository() {
        SQLiteDataSource dataSource = dataSource();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqliteSchema schema = new SqliteSchema(jdbc, dataSource);
        SourceFileStateStore stateStore = new SourceFileStateStore(jdbc, schema);
        DerivedTables derived = new DerivedTables(jdbc, transactions, schema);
        HuaweiExportImporter huawei = new HuaweiExportImporter(dataSource, jdbc, transactions,
                new HuaweiExportScanner(), new MotionPathParser(), new HealthDetailParser(),
                new MinuteActivityParser(), new SampleSequenceParser(),
                new ExcelWorkbookParser(), properties, stateStore, schema, derived);
        TcxIncrementalImporter importer = new TcxIncrementalImporter(
                jdbc, transactions, new SourceFileScanner(), new TcxParser(properties),
                properties, stateStore, schema, offlineBackfill(jdbc), huawei);
        return new JdbcHealthDataRepository(jdbc, importer, properties, schema);
    }

    private WeatherBackfill offlineBackfill(JdbcClient jdbc) {
        WeatherProperties weather = new WeatherProperties(false, null, null, 0, 0, null, 7, 50);
        OpenMeteoClient client = new OpenMeteoClient(
                org.springframework.web.reactive.function.client.WebClient.create(), weather);
        return new WeatherBackfill(new RunWeatherStore(jdbc), client, weather);
    }

    /** 每条断言单独建连接：SQLite 文件是外部状态，连接池在这里没有意义。 */
    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    private SQLiteDataSource dataSource() {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

    private void copyTcx() throws IOException {
        try (var input = new ClassPathResource(SAMPLE_TCX).getInputStream()) {
            Files.copy(input, dataDir.resolve("20261025户外跑步.tcx"));
        }
    }

    private void write(String relative, String content) throws IOException {
        Path target = dataDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
