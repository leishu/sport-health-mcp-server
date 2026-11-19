package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.config.WeatherProperties;
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
 * {@code get_run_detail} 的天气块：从 {@code run_weather} 表读到单条记录再交给工具层。
 *
 * <p>用真实 SQLite：这一层要验证的是「按 id 查询、列映射、中文描述、缺行与失败状态的表达」串起来的
 * 实际输出，mock 掉 {@code RunWeatherStore} 只能测到自己的 if。天气行直接种进表里，
 * 回填开关关掉，所以测试不联网。
 */
class SportHealthToolsWeatherTest {

    private static final String SAMPLE = "tcx-dataset/20261025户外跑步.tcx";
    private static final String RUN_ID = "20261025户外跑步";
    private static final Instant RUN_START = Instant.parse("2026-10-24T22:56:16.433Z");
    private static final String RUN_START_TEXT = "2026-10-24 22:56:16";

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path dbFile;
    private HealthDataProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("tcx"));
        dbFile = tempDir.resolve("db").resolve("sport-health.db");
        try (var input = new ClassPathResource(SAMPLE).getInputStream()) {
            Files.copy(input, dataDir.resolve(RUN_ID + ".tcx"));
        }
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @Test
    void detailCarriesTheWeatherOfThatRun() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertOkWeather(RUN_ID);

        Map<String, Object> result = tools(repository).getRunDetail(RUN_ID, false, null, null);

        assertEquals("OK", result.get("status"));
        Map<String, Object> weather = map(result.get("weather"));
        assertNotNull(weather, "JDBC 仓储下必须输出天气块");
        assertEquals("OK", weather.get("status"));
        assertEquals("open-meteo", weather.get("provider"));
        assertEquals(RUN_START_TEXT, weather.get("fetchedAt"), "时间统一为 yyyy-MM-dd HH:mm:ss（UTC）");

        Map<String, Object> start = map(weather.get("start"));
        assertEquals(RUN_START_TEXT, start.get("observedAt"), "时间统一为 yyyy-MM-dd HH:mm:ss（UTC）");
        assertEquals(8.0, start.get("temperatureC"));
        assertEquals(72.0, start.get("humidityPercent"));
        assertEquals(0.2, start.get("precipitationMm"));
        assertEquals(315.0, start.get("windDirectionDegrees"));
        assertEquals("西北", start.get("windDirection"), "315° 是西北风");
        assertEquals(61, start.get("weatherCode"));
        assertEquals("小雨", start.get("condition"));
        assertNull(weather.get("end"), "这一行只种了起跑时刻，结束时刻必须是 null 而不是空对象");
    }

    @Test
    void detailReportsMissingWeatherInsteadOfOmittingTheField() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        Map<String, Object> result = tools(repository).getRunDetail(RUN_ID, false, null, null);

        Map<String, Object> weather = map(result.get("weather"));
        assertNotNull(weather, "没回填过也要说明状态，而不是整个字段消失");
        assertEquals("MISSING", weather.get("status"));
        assertNotNull(weather.get("message"), "要告诉调用方同步一次就能补上");
    }

    @Test
    void detailExplainsWhyAWeatherRowIsUnavailable() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        jdbc().sql("""
                        INSERT INTO run_weather (run_id, provider, fetched_at, fetched_epoch_millis, status, error_message)
                        VALUES (?, 'open-meteo', ?, ?, 'UNAVAILABLE', '记录缺少有效坐标')
                        """)
                .param(RUN_ID)
                .param(RUN_START.toString())
                .param(RUN_START.toEpochMilli())
                .update();

        Map<String, Object> weather = map(tools(repository).getRunDetail(RUN_ID, false, null, null).get("weather"));

        assertEquals("UNAVAILABLE", weather.get("status"));
        assertEquals("记录缺少有效坐标", weather.get("errorMessage"));
        assertNull(weather.get("start"), "没有快照时两个时刻都是 null");
        assertNull(weather.get("end"));
    }

    @Test
    void detailOmitsWeatherWhenNoStoreIsInjected() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        // 内存仓储那条路径没有天气表，构造时传 null
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock()));
        SportHealthTools tools = new SportHealthTools(analytics, repository, properties, null);

        Map<String, Object> result = tools.getRunDetail(RUN_ID, false, null, null);

        assertEquals("OK", result.get("status"));
        assertFalse(result.containsKey("weather"), "没有天气来源时不该输出空天气块");

        Map<String, Object> storage = map(tools.describeDataSource().get("storage"));
        assertFalse(storage.containsKey("weather"), "describe_data_source 也不该输出 weather=null");
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

    /** 种一条「起跑时刻已取到」的天气：温度 8°C、湿度 72%、西北风 315°、小雨 61。 */
    private void insertOkWeather(String runId) {
        jdbc().sql("""
                INSERT INTO run_weather (
                    run_id, start_obs_time_utc, start_temperature_c, start_apparent_c,
                    start_humidity_percent, start_precipitation_mm, start_wind_speed_kmh,
                    start_wind_direction, start_weather_code,
                    provider, fetched_at, fetched_epoch_millis, status)
                VALUES (?, ?, 8.0, 6.0, 72.0, 0.2, 12.0, 315, 61, 'open-meteo', ?, ?, 'OK')
                """)
                .param(runId)
                .param(RUN_START.toString())
                .param(RUN_START.toString())
                .param(RUN_START.toEpochMilli())
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
