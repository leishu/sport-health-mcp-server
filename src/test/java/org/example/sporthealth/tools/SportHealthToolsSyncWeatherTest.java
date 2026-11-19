package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.importer.SourceFileScanner;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.importer.huawei.ExcelWorkbookParser;
import org.example.sporthealth.importer.huawei.HealthDetailParser;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner;
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
import org.junit.jupiter.api.AfterEach;
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
 * {@code sync_weather} 工具：只补天气、不重导数据，并且把「做了什么」如实返回。
 *
 * <p>用真实 SQLite + 本地接口桩：这一层的价值在于「工具输出」与「库里的状态」一致——
 * 只 mock 掉 {@code WeatherBackfill} 只能验证自己拼的 map。导入阶段一律用
 * {@code reload(false)} 跳过天气，所以初始化本身不联网，联网只发生在被测的 {@code syncWeather} 里。
 */
class SportHealthToolsSyncWeatherTest {

    private static final String SAMPLE = "tcx-dataset/20261025户外跑步.tcx";
    private static final String RUN_ID = "20261025户外跑步";
    /** 文件名里的 20261025 是本地日期，UTC 起跑时刻在前一天 22:56。 */
    private static final Instant RUN_START = Instant.parse("2026-10-24T22:56:16.433Z");

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private Path dataDir;
    private Path dbFile;
    private HealthDataProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        dataDir = Files.createDirectories(tempDir.resolve("tcx"));
        dbFile = tempDir.resolve("db").resolve("sport-health.db");
        try (var input = new ClassPathResource(SAMPLE).getInputStream()) {
            Files.copy(input, dataDir.resolve(RUN_ID + ".tcx"));
        }
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** 正常路径：先只同步数据（weather=false），再单独补天气，跑步数据不受影响且覆盖率回落为 0 缺口。 */
    @Test
    void fillsWeatherWithoutReloadingRuns() {
        respondDaily("2026-10-24");
        WeatherProperties weather = weather(true);
        JdbcHealthDataRepository repository = repository(weather);
        repository.reload(false);
        assertEquals(0, count("run_weather"), "前置条件：同步阶段跳过了天气");

        Map<String, Object> result = tools(repository, weather).syncWeather(true);

        assertEquals("OK", result.get("status"));
        assertEquals(1, result.get("ok"), "message=" + result.get("message"));
        assertEquals(0, result.get("failed"));
        assertEquals(0, result.get("noLocation"));
        assertEquals(1, result.get("attempted"));
        assertEquals(1, result.get("total"));
        assertEquals(List.of(), result.get("problems"));
        assertEquals(1, count("run_weather"), "只补天气，不新增跑步记录");
        assertEquals(1, repository.dataset().runCount());

        Map<String, Object> coverage = map(result.get("coverage"));
        assertEquals(1, coverage.get("totalRuns"));
        assertEquals(1, coverage.get("ok"));
        assertEquals(0, coverage.get("missing"));
        assertEquals(100d, (Double) coverage.get("coveragePercent"), 0.01);
    }

    /** 失败要如实回报原因，且不能把跑步数据搞坏。 */
    @Test
    void networkFailureIsReportedWithoutTouchingRuns() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        WeatherProperties weather = weather(true);
        JdbcHealthDataRepository repository = repository(weather);
        repository.reload(false);

        Map<String, Object> result = tools(repository, weather).syncWeather(true);

        assertEquals("OK", result.get("status"), "工具本身成功执行，失败的是每条记录的抓取");
        assertEquals(0, result.get("ok"));
        assertEquals(1, result.get("failed"));
        assertFalse(((List<?>) result.get("problems")).isEmpty(), "失败原因不能为空");
        assertTrue(result.get("message").toString().contains("FAILED"), result.get("message").toString());
        assertEquals(1, repository.dataset().runCount(), "跑步数据完好");
        assertEquals(RUN_ID, repository.dataset().activities().get(0).id());
    }

    /** 已经全是终态时要说清楚「没什么可补的」，而不是回一个看起来成功的空结果。 */
    @Test
    void reportsNothingToDoWhenEverythingIsTerminal() {
        respondDaily("2026-10-24");
        WeatherProperties weather = weather(true);
        JdbcHealthDataRepository repository = repository(weather);
        repository.reload(false);
        SportHealthTools tools = tools(repository, weather);
        tools.syncWeather(true);

        Map<String, Object> again = tools.syncWeather(true);

        assertEquals("OK", again.get("status"));
        assertEquals(0, again.get("total"));
        assertTrue(again.get("message").toString().contains("没有需要补天气"), again.get("message").toString());
    }

    @Test
    void reportsDisabledInsteadOfSilentlyDoingNothing() {
        WeatherProperties weather = weather(false);
        JdbcHealthDataRepository repository = repository(weather);
        repository.reload(false);

        Map<String, Object> result = tools(repository, weather).syncWeather(true);

        assertEquals("DISABLED", result.get("status"));
        assertTrue(result.get("message").toString().contains("sport.weather.enabled"));
        assertEquals(0, server.getRequestCount());
    }

    /** 没有注入回填（非 JDBC 仓储）时给 NO_DATA，而不是 NPE。 */
    @Test
    void reportsNoDataWhenBackfillIsAbsent() {
        WeatherProperties weather = weather(true);
        JdbcHealthDataRepository repository = repository(weather);
        repository.reload(false);
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock()));
        SportHealthTools tools = new SportHealthTools(analytics, repository, properties, new RunWeatherStore(jdbc()));

        Map<String, Object> result = tools.syncWeather(true);

        assertEquals("NO_DATA", result.get("status"));
        assertNotNull(result.get("message"));
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private SportHealthTools tools(JdbcHealthDataRepository repository, WeatherProperties weather) {
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock()));
        return new SportHealthTools(analytics, repository, properties,
                new RunWeatherStore(jdbc()), backfill(weather), null);
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private WeatherProperties weather(boolean enabled) {
        String baseUrl = server.url("/v1/forecast").toString();
        return new WeatherProperties(enabled, baseUrl, baseUrl, 92, 5,
                java.time.Duration.ofSeconds(5), 7, 50);
    }

    private WeatherBackfill backfill(WeatherProperties weather) {
        return new WeatherBackfill(new RunWeatherStore(jdbc()),
                new OpenMeteoClient(WebClient.create(), weather), weather);
    }

    private JdbcHealthDataRepository repository(WeatherProperties weather) {
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
                properties, stateStore, schema, backfill(weather), huawei);
        return new JdbcHealthDataRepository(jdbc, importer, properties, schema);
    }

    private int count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
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

    /** 单坐标时返回对象、多坐标时返回数组，与真实接口一致。 */
    private void respondDaily(String date) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = dayBody(date);
                int count = coordinateCount(request.getPath());
                return new MockResponse()
                        .setBody(count <= 1 ? body : "[" + String.join(",", java.util.Collections.nCopies(count, body)) + "]")
                        .addHeader("Content-Type", "application/json");
            }
        });
    }

    private static int coordinateCount(String path) {
        if (path == null) {
            return 1;
        }
        var matcher = java.util.regex.Pattern.compile("latitude=([^&]*)").matcher(path);
        if (!matcher.find()) {
            return 1;
        }
        return matcher.group(1).split(",").length;
    }

    /** 该日 24 小时完整数据：温度 5 + 0.5×小时，其余字段给常数。 */
    private static String dayBody(String date) {
        List<String> times = new java.util.ArrayList<>();
        List<String> temperatures = new java.util.ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(String.format("%sT%02d:00", date, hour));
            temperatures.add(String.valueOf(5 + hour * 0.5));
        }
        String timeArray = times.stream().map(text -> '"' + text + '"')
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"hourly":{"time":[%s],
                           "temperature_2m":[%s],
                           "apparent_temperature":[%s],
                           "relative_humidity_2m":[%s],
                           "precipitation":[%s],
                           "wind_speed_10m":[%s],
                           "wind_direction_10m":[%s],
                           "weather_code":[%s]}}
                """.formatted(timeArray, String.join(",", temperatures), String.join(",", temperatures),
                fill(24, "65"), fill(24, "0.0"), fill(24, "9.0"), fill(24, "180"), fill(24, "1"));
    }

    private static String fill(int count, String value) {
        return String.join(",", java.util.Collections.nCopies(count, value));
    }
}
