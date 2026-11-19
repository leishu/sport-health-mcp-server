package org.example.sporthealth.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.RunWeather;
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
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.SourceFileStateStore;
import org.example.sporthealth.repository.TcxIncrementalImporter;
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
 * 天气回填的端到端行为：真实 SQLite + AST 桩接口。
 *
 * <p>覆盖的是几个真正会出问题的点：
 * <ul>
 *   <li>回填写进去了没有、字段对不对；</li>
 *   <li>接口整体没覆盖该时段时落 {@code UNAVAILABLE}，不能落 {@code OK}；</li>
 *   <li>网络故障落 {@code FAILED}，且<b>不影响跑步数据</b>（这是最容易破坏的一条）；</li>
 *   <li>失败的重试冷却：刚失败的不会立刻再打接口，过期后会重试；</li>
 *   <li>重复同步不会重复插入，{@code run_weather} 一行对一次跑步。</li>
 * </ul>
 */
class WeatherBackfillTest {

    private static final String SAMPLE = "tcx-dataset/20261025户外跑步.tcx";

    /**
     * 样本记录的起跑时间（UTC）。
     *
     * <p>注意：文件名「20261025」是<b>本地日期</b>（Asia/Shanghai），换算成 UTC 是前一天 22:56。
     * 天气按 UTC 日期取数，若这里按文件名写 10-25，请求到的就是空数据。
     */
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
            Files.copy(input, dataDir.resolve("20261025户外跑步.tcx"));
        }
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void backfillsStartAndEndWeatherIntoTheDatabase() throws Exception {
        // 样本：起跑 2026-10-24T22:56:16Z，时长 2448 秒（约 41 分钟），结束在 23:37，同一天
        respondDaily("2026-10-24");
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();

        Map<String, Object> row = weatherRow();
        assertEquals("OK", row.get("status"));
        assertNotNull(row.get("start_temperature_c"));
        assertNotNull(row.get("end_temperature_c"));
        assertNotNull(row.get("start_apparent_c"), "体感温度要保留");
        assertNotNull(row.get("end_humidity_percent"));
        assertEquals("open-meteo", row.get("provider"));
        assertEquals(1, count("run_weather"), "一行对一次跑步");
        assertEquals(1, server.getRequestCount(), "起终点同处一天时只需一次请求");
    }

    /** 接口正常返回但全是 null：说明这个时段没有数据，落 UNAVAILABLE 而不是 OK。 */
    @Test
    void allNullHoursBecomeUnavailableInsteadOfOk() {
        respondNullHours();
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();

        Map<String, Object> row = weatherRow();
        assertEquals(RunWeather.Status.UNAVAILABLE.name(), row.get("status"));
        assertNull(row.get("start_temperature_c"), "缺数据必须是 null，绝不能是 0");
        assertNotNull(row.get("error_message"));
    }

    /** 最关键的一条：天气抓不到，跑步数据必须完好无损。 */
    @Test
    void networkFailureDoesNotAffectRunImport() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();

        assertEquals(1, repository.dataset().runCount(), "跑步数据照常入库");
        assertEquals(1216, repository.route("20261025户外跑步").size(), "轨迹也完好");
        Map<String, Object> row = weatherRow();
        assertEquals(RunWeather.Status.FAILED.name(), row.get("status"));
        assertTrue(repository.lastSync().warnings().stream()
                        .anyMatch(warning -> warning.contains("天气回填失败")),
                "失败要能在 warnings 里看到: " + repository.lastSync().warnings());
    }

    /**
     * 冷却期内的 FAILED 不再打接口；超过冷却时间才重试。
     *
     * <p>时间轴必须由 {@code now} 显式驱动：{@code fetched_epoch_millis} 记的是回填那一轮的
     * 基准时刻。用 {@code Instant.now()} 的真实时间做首次失败、再用未来时刻模拟「几天后」，
     * 会让两条时间轴错位——真实时间永远追不上注入的未来时间，冷却看起来永远不过期。
     */
    @Test
    void retriesFailedRecordsOnlyAfterTheCooldown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        WeatherBackfill first = backfill(weather(7, 50, 92));
        // 导入把 run_activity 灌好，同时产生第一次失败的天气记录
        repository(weather(7, 50, 92), first).dataset();
        Map<String, Object> failedRow = weatherRow();
        assertEquals(RunWeather.Status.FAILED.name(), failedRow.get("status"),
                "失败原因: " + failedRow.get("error_message"));

        // 把失败行的时间戳归零到起跑当天，之后用 now 精确控制时间轴。
        // 不这么做的话这里有两条互不相关的时间轴：行上是真实运行时刻，断言用的是注入的未来时刻。
        jdbc().sql("UPDATE run_weather SET fetched_epoch_millis = ?, fetched_at = ?")
                .param(RUN_START.toEpochMilli())
                .param(RUN_START.toString())
                .update();
        int requestsAfterFirst = server.getRequestCount();

        // 第二天再同步：仍在 7 天冷却内，不应发请求
        WeatherBackfill.Result withinCooldown = first.run(RUN_START.plusSeconds(86_400));
        assertEquals(requestsAfterFirst, server.getRequestCount(), "冷却期内不得重试");
        assertEquals(0, withinCooldown.attempted(), "冷却期内连候选都不该选出来");

        // 第六天：仍在冷却内（仅第二天断言了，这里覆盖中间时刻，避免把「刚好边界」当常态）
        WeatherBackfill.Result stillWithinCooldown = first.run(RUN_START.plusSeconds(6 * 86_400L));
        assertEquals(requestsAfterFirst, server.getRequestCount(), "第六天仍在 7 天冷却内");
        assertEquals(0, stillWithinCooldown.attempted());

        // 第八天：冷却已过，重试并成功
        respondDaily("2026-10-24");
        WeatherBackfill.Result result = first.run(RUN_START.plusSeconds(8 * 86_400L));
        assertEquals(1, result.ok(), "重试结果: attempted=" + result.attempted() + " failed=" + result.failed()
                + " unavailable=" + result.unavailable() + " problems=" + result.problems());
        assertEquals(RunWeather.Status.OK.name(), weatherRow().get("status"));
    }

    /** 重复同步必须幂等：一行对一次跑步，不新增、不重复请求。 */
    @Test
    void secondSyncDoesNotRefetchOrDuplicate() {
        respondDaily("2026-10-24");
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();
        int requestsAfterFirst = server.getRequestCount();

        repository.reload();

        assertEquals(requestsAfterFirst, server.getRequestCount(), "已成功的记录不再查询");
        assertEquals(1, count("run_weather"));
    }

    /** 没有 GPS 的记录查不了天气，直接落 UNAVAILABLE，连请求都不发。 */
    @Test
    void runWithoutCoordinatesSkipsTheRequest() throws IOException {
        Files.writeString(dataDir.resolve("20261026无坐标.tcx"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-10-26T01:00:00.000Z</Id>
                      <Lap StartTime="2026-10-26T01:00:00.000Z">
                        <TotalTimeSeconds>120.0</TotalTimeSeconds>
                        <DistanceMeters>400.0</DistanceMeters>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """);
        respondDaily("2026-10-24");
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();

        String status = jdbc().sql("SELECT status FROM run_weather WHERE run_id = ?")
                .param("20261026无坐标")
                .query(String.class)
                .single();
        assertEquals(RunWeather.Status.UNAVAILABLE.name(), status, "缺坐标只标不可用，不发请求");
        assertEquals(2, count("run_weather"), "两条都有一行：缺坐标的标 UNAVAILABLE，有轨迹的照常回填");
        assertEquals(1, server.getRequestCount(), "只有有轨迹的那条发一次请求，无坐标那条不发");
    }

    /** 关闭开关后完全不碰网络，表里也不留行。 */
    @Test
    void disabledWeatherSkipsEverything() {
        JdbcHealthDataRepository repository = repository(
                new WeatherProperties(false, null, null, 92, 5, null, 7, 50));
        repository.dataset();

        assertEquals(0, server.getRequestCount());
        assertEquals(0, count("run_weather"));
        assertEquals(1, repository.dataset().runCount(), "跑步数据照常入库");
    }

    /**
     * 跨 UTC 零点的跑步要分别请求两天，结束时刻的快照必须来自次日。
     *
     * <p>这条用例之前写成了自证式断言（只算了一个局部 {@link Instant}），
     * 生产代码里的 {@code endOf} 与按日期分组逻辑完全没被覆盖。
     */
    @Test
    void crossMidnightRunQueriesBothUtcDays() throws Exception {
        schema();
        Instant startUtc = Instant.parse("2026-10-24T23:30:00Z");
        insertRun("跨天", startUtc, 3600, 39.90, 116.18, 39.90, 116.18);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                boolean nextDay = path != null && path.contains("start_date=2026-10-25");
                return json(dayBody(nextDay ? "2026-10-25" : "2026-10-24", nextDay ? 100 : 0));
            }
        });

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(startUtc);

        assertEquals(1, result.ok(), "problems=" + result.problems());
        assertEquals(2, server.getRequestCount(), "起跑与结束分属两个 UTC 日期");
        Map<String, Object> row = jdbc().sql("""
                SELECT start_obs_time_utc, end_obs_time_utc, start_temperature_c, end_temperature_c
                FROM run_weather
                """).query().singleRow();
        assertEquals("2026-10-24T23:00:00Z", row.get("start_obs_time_utc"));
        assertEquals("2026-10-25T00:00:00Z", row.get("end_obs_time_utc"));
        double start = ((Number) row.get("start_temperature_c")).doubleValue();
        double end = ((Number) row.get("end_temperature_c")).doubleValue();
        assertTrue(start < 20, "起点用 10-24 的序列: " + start);
        assertTrue(end > 100, "终点必须用 10-25 的序列: " + end);
    }

    /**
     * 跨天记录一天返回 400（终态）、另一天返回 503（可重试）时，整条记录要落 FAILED，
     * 不能因为起跑日先失败就被永久判成 UNAVAILABLE。
     */
    @Test
    void retryableFailureWinsOverClientErrorOnTheOtherDay() {
        schema();
        Instant startUtc = Instant.parse("2026-10-24T23:30:00Z");
        insertRun("跨天失败", startUtc, 3600, 39.90, 116.18, 39.90, 116.18);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                boolean startDay = path != null && path.contains("start_date=2026-10-24");
                return new MockResponse().setResponseCode(startDay ? 400 : 503);
            }
        });

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(startUtc);

        assertEquals(1, result.failed(), "可重试失败必须优先，不能因为起跑日是 400 就永久放弃");
        Map<String, Object> row = weatherRow();
        assertEquals(RunWeather.Status.FAILED.name(), row.get("status"));
        assertTrue(String.valueOf(row.get("error_message")).contains("503"),
                String.valueOf(row.get("error_message")));
    }

    /**
     * 手动同步（{@code sync_weather}）忽略冷却：失败的记录立刻重试，不用等 7 天。
     *
     * <p>没有这一条，用户看到 FAILED 后手动补一轮会「什么都没发生」——{@code pending}
     * 被冷却挡在外面，返回全 0，看起来跟工具坏了没区别。
     */
    @Test
    void forcedRetryIgnoresTheCooldown() {
        server.enqueue(new MockResponse().setResponseCode(503));
        WeatherBackfill backfill = backfill(weather(7, 50, 92));
        repository(weather(7, 50, 92), backfill).dataset();
        assertEquals(RunWeather.Status.FAILED.name(), weatherRow().get("status"));

        // 把失败行的时间戳归零，之后用 now 精确控制时间轴（同冷却用例的理由）
        jdbc().sql("UPDATE run_weather SET fetched_epoch_millis = ?, fetched_at = ?")
                .param(RUN_START.toEpochMilli())
                .param(RUN_START.toString())
                .update();
        int requestsAfterFirst = server.getRequestCount();

        WeatherBackfill.Result withinCooldown = backfill.run(RUN_START.plusSeconds(86_400));
        assertEquals(0, withinCooldown.attempted(), "对照组：普通 run 在冷却期内不选候选");
        assertEquals(requestsAfterFirst, server.getRequestCount());

        respondDaily("2026-10-24");
        WeatherBackfill.Result forced = backfill.run(RUN_START.plusSeconds(86_400), true);

        assertEquals(1, forced.ok(), "强制重试应当立刻成功：attempted=" + forced.attempted()
                + " failed=" + forced.failed() + " problems=" + forced.problems());
        assertEquals(requestsAfterFirst + 1, server.getRequestCount(), "强制重试必须真的发一次请求");
        assertEquals(RunWeather.Status.OK.name(), weatherRow().get("status"));
    }

    /**
     * {@code reload(weather=false)} 只同步数据、不碰网络。
     *
     * <p>端点是黑洞时，一次 {@code reload_data} 会被「3 次超时」拖住几十秒，
     * MCP 客户端通常先断开；用户只想把新数据导进来时必须能跳过天气。
     */
    @Test
    void reloadWithoutWeatherSkipsTheNetworkEntirely() {
        respondDaily("2026-10-24");
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));

        repository.reload(false);

        assertEquals(0, server.getRequestCount(), "weather=false 不得发起任何请求");
        assertEquals(0, count("run_weather"), "跳过天气就不该落行");
        assertEquals(1, repository.dataset().runCount(), "跑步数据照常入库");

        // 之后再单独补：这次才会联网
        repository.reload(true);
        assertEquals(1, server.getRequestCount());
        assertEquals(RunWeather.Status.OK.name(), weatherRow().get("status"));
    }

    /**
     * 只给跑步记录补天气：{@code run_activity} 里还有步行等其它运动，它们不该消耗配额，
     * 也不该算进「总跑步数」——否则覆盖率的分母会大于 {@code describe_data_source.runningCount}。
     */
    @Test
    void nonRunningActivitiesAreNeitherBackfilledNorCounted() {
        schema();
        Instant startUtc = Instant.parse("2026-10-24T02:00:00Z");
        insertRun("跑步", startUtc, 1800, 39.90, 116.18, 39.90, 116.18);
        insertRun("步行", startUtc.plusSeconds(3600), 1800, 39.91, 116.19, 39.91, 116.19);
        jdbc().sql("UPDATE run_activity SET sport_family = 'WALK' WHERE id = '步行'").update();
        respondDaily("2026-10-24");

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(startUtc);

        assertEquals(1, result.total(), "只有跑步记录参与回填");
        assertEquals(1, count("run_weather"), "步行不落天气行");
        RunWeatherStore.Stats stats = new RunWeatherStore(jdbc()).stats();
        assertEquals(1, stats.totalRuns(), "总跑步数不含步行");
        assertEquals(1, stats.ok());
    }

    @Test
    void statsReportCoverage() {
        respondDaily("2026-10-24");
        JdbcHealthDataRepository repository = repository(weather(7, 50, 92));
        repository.dataset();

        RunWeatherStore.Stats stats = new RunWeatherStore(jdbc()).stats();
        assertEquals(1, stats.totalRuns());
        assertEquals(1, stats.ok());
        assertEquals(0, stats.failed());
        assertEquals(0, stats.missing());
        assertNotNull(stats.lastFetchedAt());
    }

    /**
     * 同一天的多条记录合并成一次请求：hourly 只能按天取，坐标可以在一次请求里合并，
     * 不合并的话配额按「记录数」而不是「日期数」消耗。
     */
    @Test
    void sameDayRunsShareASingleRequest() throws Exception {
        schema();
        Instant startUtc = Instant.parse("2026-10-24T02:00:00Z");
        insertRun("同日A", startUtc, 1800, 39.90, 116.18, 39.90, 116.18);
        insertRun("同日B", startUtc.plusSeconds(600), 1800, 39.95, 116.25, 39.95, 116.25);
        respondDaily("2026-10-24");

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(startUtc);

        assertEquals(1, server.getRequestCount(), "两条记录共用一次请求");
        assertEquals(2, result.ok(), "problems=" + result.problems());
        String path = server.takeRequest().getPath();
        assertTrue(path.contains("39.9000") && path.contains("39.9500"), "两个坐标一次发出去: " + path);
    }

    /**
     * 回归用例：起终点同一天但相距很远（点对点跑步）时，终点必须用自己坐标的序列。
     *
     * <p>早期实现按「日期」做缓存键，终点会静默复用起点的响应——跑到另一个城市也照样报出起点的天气。
     */
    @Test
    void endSnapshotUsesTheEndCoordinateNotTheStartOne() throws Exception {
        schema();
        Instant startUtc = Instant.parse("2026-10-24T02:00:00Z");
        insertRun("点对点", startUtc, 3600, 39.90, 116.18, 31.20, 121.40);
        respondDailyWithDistinctSeries("2026-10-24");

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(startUtc);

        assertEquals(1, result.ok(), "problems=" + result.problems());
        String path = server.takeRequest().getPath();
        assertTrue(path.contains("39.9000") && path.contains("31.2000"), "起点和终点坐标都要发出去: " + path);
        Map<String, Object> row = weatherRow();
        double start = ((Number) row.get("start_temperature_c")).doubleValue();
        double end = ((Number) row.get("end_temperature_c")).doubleValue();
        assertTrue(start < 20, "起点用第 0 份序列: " + start);
        assertTrue(end > 40, "终点必须用自己坐标的序列（+50 度），而不是复用的起点: " + end);
    }

    /**
     * 端点持续不可用时不能一条条等超时：连续失败到阈值就收尾，
     * 没轮到的记录不落行，下次同步会重新选中——不能因为一次网络故障把整批打进 7 天冷却。
     */
    @Test
    void consecutiveFailuresStopTheRoundEarly() {
        schema();
        Instant first = Instant.parse("2026-10-20T02:00:00Z");
        for (int day = 0; day < 5; day++) {
            Instant startUtc = first.plusSeconds(day * 86_400L);
            insertRun("连续失败" + day, startUtc, 1800, 39.90, 116.18, 39.90, 116.18);
        }
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(503);
            }
        });

        WeatherBackfill.Result result = backfill(weather(7, 50, 92)).run(first.plusSeconds(4 * 86_400L));

        assertEquals(WeatherBackfill.MAX_CONSECUTIVE_FAILURES, server.getRequestCount(), "连续失败后不再请求");
        assertEquals(WeatherBackfill.MAX_CONSECUTIVE_FAILURES, result.failed());
        assertEquals(2, result.deferred(), "没轮到的记录不落状态，下次继续");
        assertEquals(WeatherBackfill.MAX_CONSECUTIVE_FAILURES, count("run_weather"), "只有请求过的才有行");
    }

    // ------------------------------------------------------------------

    private WeatherProperties weather(int retryDays, int maxRuns, int windowDays) {
        String baseUrl = server.url("/v1/forecast").toString();
        return new WeatherProperties(true, baseUrl, baseUrl, windowDays, 5,
                java.time.Duration.ofSeconds(5), retryDays, maxRuns);
    }

    private WeatherBackfill backfill(WeatherProperties weather) {
        return new WeatherBackfill(new RunWeatherStore(jdbc()),
                new OpenMeteoClient(WebClient.create(), weather), weather);
    }

    private JdbcHealthDataRepository repository(WeatherProperties weather) {
        return repository(weather, backfill(weather));
    }

    private JdbcHealthDataRepository repository(WeatherProperties weather, WeatherBackfill backfill) {
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
                properties, stateStore, schema, backfill, huawei);
        return new JdbcHealthDataRepository(jdbc, importer, properties, schema);
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

    private Map<String, Object> weatherRow() {
        return jdbc().sql("""
                SELECT status, provider, start_temperature_c, start_apparent_c,
                       end_temperature_c, end_humidity_percent, error_message, fetched_at
                FROM run_weather
                """).query().singleRow();
    }

    private int count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /** 真实接口返回 application/json，测试桩同样带上。 */
    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    /**
     * 按请求里的坐标个数回对象或数组：真实接口在多坐标时返回 JSON 数组。
     *
     * <p>桩必须跟着变，否则「同一天合并成一次请求」这条路径根本跑不通——
     * 请求里有几个坐标，响应就得返回几个元素，否则客户端会当成解析失败。
     */
    private void respondWith(Supplier<String> single) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = single.get();
                int count = coordinateCount(request.getPath());
                return json(count <= 1
                        ? body
                        : "[" + String.join(",", Collections.nCopies(count, body)) + "]");
            }
        });
    }

    private void respondDaily(String date) {
        respondWith(() -> dayBody(date));
    }

    private void respondNullHours() {
        respondWith(WeatherBackfillTest::nullHoursBody);
    }

    /** 每个坐标返回不同的温度（第 i 个坐标整体加 50×i 度），用于验证终点用的是自己的序列。 */
    private void respondDailyWithDistinctSeries(String date) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int count = coordinateCount(request.getPath());
                List<String> bodies = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    bodies.add(dayBody(date, 50 * index));
                }
                return json(count <= 1
                        ? bodies.get(0)
                        : "[" + String.join(",", bodies) + "]");
            }
        });
    }

    /** 请求里 {@code latitude=} 后面逗号分隔了几个坐标。 */
    private static int coordinateCount(String path) {
        if (path == null) {
            return 1;
        }
        Matcher matcher = Pattern.compile("latitude=([^&]*)").matcher(path);
        if (!matcher.find()) {
            return 1;
        }
        return matcher.group(1).split(",").length;
    }

    /** 不绕 TCX 解析直接种一条 run_activity：这些用例只关心天气回填。 */
    private void insertRun(String id, Instant startUtc, double durationSeconds,
                           Double startLat, Double startLon, Double endLat, Double endLon) {
        jdbc().sql("""
                INSERT INTO run_activity (
                    id, activity_id, sport, run_date, start_time, end_time, start_time_utc,
                    duration_seconds, distance_meters, track_point_count,
                    start_latitude, start_longitude, end_latitude, end_longitude,
                    source_file, content_hash, imported_at)
                VALUES (?, ?, '户外跑步', ?, ?, ?, ?, ?, 5000.0, 0, ?, ?, ?, ?, ?, 'hash', ?)
                """)
                .param(id)
                .param(id)
                .param(LocalDate.ofInstant(startUtc, ZoneOffset.UTC).toString())
                .param(startUtc.toString())
                .param(startUtc.plusSeconds(Math.round(durationSeconds)).toString())
                .param(startUtc.toString())
                .param(durationSeconds)
                .param(startLat)
                .param(startLon)
                .param(endLat)
                .param(endLon)
                .param(id + ".tcx")
                .param(startUtc.toString())
                .update();
    }

    /** 直接种数据前先建表：没走仓储时没人触发迁移。 */
    private void schema() {
        new SqliteSchema(jdbc(), dataSource()).ensure();
    }

    /** 指定日的 24 小时完整数据，温度按小时线性递增，便于验证插值。 */
    private static String dayBody(String date) {
        return dayBody(date, 0);
    }

    /**
     * @param offset 整体温度偏移，用于让不同坐标返回可区分的序列
     */
    private static String dayBody(String date, double offset) {
        List<String> times = new ArrayList<>();
        List<String> temperatures = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(String.format("%sT%02d:00", date, hour));
            temperatures.add(String.valueOf(offset + 5 + hour * 0.5));
        }
        return buildBody(times, temperatures);
    }

    /**
     * 拼装 open-meteo 的响应体。
     *
     * <p>时间戳本身带引号，所以这里逐项加引号后再 join——
     * 之前用 {@code split(",")} 反解析字符串的写法会把引号一起拆坏，时间轴全部变成 null。
     */
    private static String buildBody(List<String> times, List<String> temperatures) {
        String timeArray = times.stream().map(text -> '"' + text + '"')
                .collect(java.util.stream.Collectors.joining(","));
        String apparenArray = String.join(",", temperatures);
        return """
                {"hourly":{"time":[%s],
                           "temperature_2m":[%s],
                           "apparent_temperature":[%s],
                           "relative_humidity_2m":[%s],
                           "precipitation":[%s],
                           "wind_speed_10m":[%s],
                           "wind_direction_10m":[%s],
                           "weather_code":[%s]}}
                """.formatted(timeArray, String.join(",", temperatures), apparenArray,
                fill(24, "65"), fill(24, "0.0"), fill(24, "9.0"), fill(24, "180"), fill(24, "1"));
    }

    /** 该日 24 小时全是 null，模拟「接口正常但没有这个时段的数据」。 */
    private static String nullHoursBody() {
        List<String> times = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            times.add(String.format("2026-10-24T%02d:00", hour));
        }
        String timeArray = times.stream().map(text -> '"' + text + '"')
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"hourly":{"time":[%s],"temperature_2m":[%s],"apparent_temperature":[%s]}}
                """.formatted(timeArray, fill(24, "null"), fill(24, "null"));
    }

    private static String fill(int count, String value) {
        return String.join(",", java.util.Collections.nCopies(count, value));
    }

    /** 保证样本文件的起跑时间与本测试常量一致，改了数据集要同步。 */
    @Test
    void sampleDatasetStartTimeMatchesTheConstant() {
        JdbcHealthDataRepository repository = repository(
                new WeatherProperties(false, null, null, 92, 5, null, 7, 50));
        repository.dataset();
        String startUtc = jdbc().sql("SELECT start_time_utc FROM run_activity").query(String.class).single();
        assertEquals(RUN_START.toString(), startUtc);
        assertFalse(repository.lastSync().warnings().stream().anyMatch(w -> w.contains("天气回填异常")));
    }

    /** 未启用时 {@code Result} 全 0，且不产生任何副作用。 */
    @Test
    void skippedResultIsAllZero() {
        List<String> problems = WeatherBackfill.Result.skipped().problems();
        assertTrue(problems.isEmpty());
        assertEquals(0, WeatherBackfill.Result.skipped().total());
    }
}
