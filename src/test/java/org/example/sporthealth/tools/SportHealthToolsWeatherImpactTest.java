package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.WeatherImpact;
import org.example.sporthealth.importer.SourceFileScanner;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.importer.huawei.HealthDetailParser;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner;
import org.example.sporthealth.importer.huawei.ExcelWorkbookParser;
import org.example.sporthealth.importer.huawei.MinuteActivityParser;
import org.example.sporthealth.importer.huawei.MotionPathParser;
import org.example.sporthealth.importer.huawei.SampleSequenceParser;
import org.example.sporthealth.repository.DerivedTables;
import org.example.sporthealth.repository.HuaweiExportImporter;
import org.example.sporthealth.repository.JdbcHealthDataRepository;
import org.example.sporthealth.repository.RunWeatherStore;
import org.example.sporthealth.repository.SourceFileStateStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.TcxIncrementalImporter;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.example.sporthealth.weather.OpenMeteoClient;
import org.example.sporthealth.weather.WeatherBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_weather_impact} 的正向路径：真实 SQLite + 5 次跑步 + 手工种天气。
 *
 * <p>验证按温度、湿度、风速、降水分组后的样本数、加权配速与相关系数。天气行直接写表，
 * 回填开关关闭，测试不联网。
 */
class SportHealthToolsWeatherImpactTest {

    private static final List<String> RUN_IDS = List.of(
            "20261025户外跑步",
            "20261027户外跑步",
            "20261028户外跑步",
            "20261101户外跑步",
            "20261103户外跑步");

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path dbFile;
    private HealthDataProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("tcx"));
        dbFile = tempDir.resolve("db").resolve("sport-health.db");
        for (String runId : RUN_IDS) {
            try (var input = new ClassPathResource("tcx-dataset/" + runId + ".tcx").getInputStream()) {
                Files.copy(input, dataDir.resolve(runId + ".tcx"));
            }
        }
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @Test
    void groupsRunsByWeatherAndComparesPace() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertWeather("20261025户外跑步", 18d, 60d, 8d, 0d);
        insertWeather("20261027户外跑步", 22d, 70d, 12d, 0d);
        insertWeather("20261028户外跑步", 25d, 80d, 15d, 0d);
        insertWeather("20261101户外跑步", 20d, 90d, 22d, 2.0d);
        insertWeather("20261103户外跑步", 10d, 55d, 5d, 0d);

        Map<String, Object> result = tools(repository).getWeatherImpact(null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals(5, result.get("totalRuns"));
        assertEquals(5, result.get("sampleSize"));
        assertEquals(344.6, (Double) result.get("baselinePaceSecondsPerKm"), 0.5);
        assertNotNull(result.get("baselinePaceText"));
        assertNotNull(result.get("temperaturePaceCorrelation"));

        List<?> temperatureBuckets = (List<?>) result.get("temperatureBuckets");
        assertEquals(4, temperatureBuckets.size(), "10-15 / 15-20 / 20-25 / 25 及以上");
        assertEquals(2, bucket(temperatureBuckets, "20-25°C").runCount());
        assertEquals(1, bucket(temperatureBuckets, "25°C 及以上").runCount());

        WeatherImpact.Bucket dry = (WeatherImpact.Bucket) result.get("dry");
        WeatherImpact.Bucket wet = (WeatherImpact.Bucket) result.get("wet");
        assertEquals(4, dry.runCount());
        assertEquals(1, wet.runCount());
        assertNotNull(wet.avgPaceSecondsPerKm());
        assertNotNull(wet.paceDeltaSecondsPerKm());

        List<?> humidityBuckets = (List<?>) result.get("humidityBuckets");
        assertEquals(2, bucket(humidityBuckets, "80% 及以上").runCount());
        List<?> windBuckets = (List<?>) result.get("windBuckets");
        assertEquals(2, bucket(windBuckets, "10-20 km/h").runCount());

        assertNotNull(result.get("text"));
    }

    @Test
    void reportsNoDataWhenNoWeatherRowsExist() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        Map<String, Object> result = tools(repository).getWeatherImpact(null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertTrue(result.get("detail").toString().contains("reload_data"));

        // NO_DATA 时最需要覆盖率：是「区间内本来没有跑步」还是「有跑步但天气全缺」，处理方式完全不同
        Map<String, Object> coverage = map(result.get("weatherCoverage"));
        assertNotNull(coverage, "缺数据时必须说明覆盖率，而不是只回一句没有数据");
        assertEquals(5, coverage.get("totalRuns"));
        assertEquals(0, coverage.get("ok"));
        assertEquals(5, coverage.get("missing"));
        assertEquals(5, coverage.get("notFetched"));
        assertTrue(coverage.get("hint").toString().contains("sync_weather"),
                "要指向新工具而不是让人再跑一次全量同步: " + coverage.get("hint"));
    }

    /** 覆盖率要能把「缺失」拆开：抓取失败可以重试，数据源无该时段重试无效，两者不能混为一谈。 */
    @Test
    void coverageSplitsMissingRunsByReason() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertWeather("20261025户外跑步", 18d, 60d, 8d, 0d);
        insertWeather("20261027户外跑步", 22d, 70d, 12d, 0d);
        insertWeather("20261028户外跑步", 25d, 80d, 15d, 0d);
        insertWeatherWithStatus("20261101户外跑步", "FAILED");
        // 20261103 压根没有天气行

        Map<String, Object> result = tools(repository).getWeatherImpact(null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals(3, result.get("sampleSize"), "只有 OK 且带快照的记录进样本");
        Map<String, Object> coverage = map(result.get("weatherCoverage"));
        assertEquals(5, coverage.get("totalRuns"));
        assertEquals(3, coverage.get("ok"));
        assertEquals(2, coverage.get("missing"));
        assertEquals(1, coverage.get("failed"));
        assertEquals(0, coverage.get("unavailable"));
        assertEquals(1, coverage.get("notFetched"));
        assertEquals(60d, (Double) coverage.get("coveragePercent"), 0.01);
        assertTrue(coverage.get("hint").toString().contains("sync_weather"));
    }

    @Test
    void minDistanceFiltersShortRuns() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertWeather("20261025户外跑步", 18d, 60d, 8d, 0d);
        insertWeather("20261027户外跑步", 22d, 70d, 12d, 0d);
        insertWeather("20261028户外跑步", 25d, 80d, 15d, 0d);
        insertWeather("20261101户外跑步", 20d, 90d, 22d, 2.0d);
        insertWeather("20261103户外跑步", 10d, 55d, 5d, 0d);

        Map<String, Object> result = tools(repository).getWeatherImpact(null, null, 8d);

        assertEquals("OK", result.get("status"));
        assertEquals(2, result.get("sampleSize"), "只看 ≥8 公里的 11-01 与 11-03");
    }

    private static WeatherImpact.Bucket bucket(List<?> buckets, String label) {
        return buckets.stream()
                .map(WeatherImpact.Bucket.class::cast)
                .filter(bucket -> label.equals(bucket.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有分组：" + label));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private SportHealthTools tools(JdbcHealthDataRepository repository) {
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock()));
        return new SportHealthTools(analytics, repository, properties, new RunWeatherStore(jdbc()));
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private void insertWeather(String runId, double temperatureC, double humidityPercent,
                               double windSpeedKmh, double precipitationMm) {
        jdbc().sql("""
                INSERT INTO run_weather (
                    run_id, start_temperature_c, start_apparent_c,
                    start_humidity_percent, start_precipitation_mm, start_wind_speed_kmh,
                    provider, fetched_at, fetched_epoch_millis, status)
                VALUES (?, ?, ?, ?, ?, ?, 'open-meteo', ?, ?, 'OK')
                """)
                .param(runId)
                .param(temperatureC)
                .param(temperatureC - 1d)
                .param(humidityPercent)
                .param(precipitationMm)
                .param(windSpeedKmh)
                .param(Instant.parse("2026-11-04T00:00:00Z").toString())
                .param(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli())
                .update();
    }

    /** 只标一条状态行、不带快照：用于验证「失败/无数据」在覆盖率里的归类。 */
    private void insertWeatherWithStatus(String runId, String status) {
        jdbc().sql("""
                INSERT INTO run_weather (run_id, provider, fetched_at, fetched_epoch_millis, status, error_message)
                VALUES (?, 'open-meteo', ?, ?, ?, '模拟失败')
                """)
                .param(runId)
                .param(Instant.parse("2026-11-04T00:00:00Z").toString())
                .param(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli())
                .param(status)
                .update();
    }

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

    /** 本测试只需要「导入 + 手工种天气」，回填必须关掉，否则会真的去访问 open-meteo。 */
    private WeatherBackfill offlineBackfill(JdbcClient jdbc) {
        WeatherProperties weather = new WeatherProperties(false, null, null, 0, 0, null, 7, 50);
        OpenMeteoClient client = new OpenMeteoClient(WebClient.create(), weather);
        return new WeatherBackfill(new RunWeatherStore(jdbc), client, weather);
    }

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
}
