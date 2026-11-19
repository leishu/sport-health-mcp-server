package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 参数校验口径：不传 = 用默认值，传了但值非法 = {@code INVALID_ARGUMENT}。
 *
 * <p>这一组用例守的是「返回的数据必须和请求对得上」：过去的实现会把 0/负数 {@code splitKm}
 * 静默当 1 公里、把全非法的 {@code distancesKm} 变成空目标再用「目标距离超过轨迹距离」解释、
 * 把认不出的 {@code category} 静默当成 {@code all}，调用方从返回体里看不出参数被改写过。
 */
class SportHealthToolsArgumentValidationTest {

    private static final String RUN_ID = "20261025户外跑步";

    private SportHealthTools tools;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties = new HealthDataProperties(
                "classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
        tools = new SportHealthTools(analytics, repository, properties, null);
    }

    @Test
    void runSplitsRejectsNonPositiveSplitKm() {
        for (double bad : new double[] {0d, -2d, 0.01d, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertInvalidArgument(tools.getRunSplits(RUN_ID, bad), "splitKm");
        }
        // 不传走默认 1 公里；恰好在下限上的 0.1 公里是合法的
        assertEquals("OK", tools.getRunSplits(RUN_ID, null).get("status"));
        assertEquals(1000d, ((Map<?, ?>) tools.getRunSplits(RUN_ID, null)).get("splitMeters"));
        assertEquals("OK", tools.getRunSplits(RUN_ID, 0.1d).get("status"));
    }

    @Test
    void bestEffortsRejectsInvalidTargets() {
        // 之前 [-1] 会被过滤成空目标，再回一句「所有目标距离都超过本次轨迹距离」，属于答非所问
        Map<String, Object> allInvalid = tools.getBestEfforts(RUN_ID, List.of(-1d));
        assertInvalidArgument(allInvalid, "distancesKm");
        assertTrue(((String) allInvalid.get("message")).contains("-1"), "提示要指出是哪一项非法");

        // 部分非法也整体拒绝，不做「能算几个算几个」的部分成功
        Map<String, Object> partlyInvalid = tools.getBestEfforts(RUN_ID, List.of(1d, 0d, 5d));
        assertInvalidArgument(partlyInvalid, "distancesKm");
        assertTrue(((String) partlyInvalid.get("message")).contains("0"));
        assertFalse(partlyInvalid.containsKey("efforts"), "校验失败不应夹杂业务数据");

        // 空列表等价于没传
        assertEquals("OK", tools.getBestEfforts(RUN_ID, List.of()).get("status"));
    }

    /** 目标都合法但跑不到时，提示必须说清是「轨迹比目标短」，并给出最小目标距离。 */
    @Test
    void bestEffortsExplainsTooShortTrack() {
        Map<String, Object> result = tools.getBestEfforts(RUN_ID, List.of(100d));

        assertEquals("OK", result.get("status"));
        assertTrue(((List<?>) result.get("unavailableTargetsMeters")).contains(100000d));
        String message = (String) result.get("message");
        assertNotNull(message, "全不可用时要给解释");
        assertTrue(message.contains("100"), "要说明最小目标距离，实际：" + message);
        assertTrue(message.contains("distancesKm"), "要指出该调哪个参数，实际：" + message);
    }

    @Test
    void personalBestsRejectsUnknownCategoryAndInvalidDistances() {
        assertInvalidArgument(tools.getPersonalBests(null, null, "best7k", null), "category");
        assertInvalidArgument(tools.getPersonalBests(null, null, "随便", null), "category");
        assertInvalidArgument(tools.getPersonalBests(null, null, "all", List.of(-1d)), "distancesKm");

        // 别名与大小写/连字符照旧宽松，不能被校验拦掉
        assertEquals("best5k", tools.getPersonalBests(null, null, "Best-5K", null).get("category"));
        assertEquals("bestHalf", tools.getPersonalBests(null, null, "半马", null).get("category"));
        // 空列表 = 没传，回落到默认 [1,5]
        assertEquals(List.of(1d, 5d), tools.getPersonalBests(null, null, "all", List.of()).get("effortDistancesKm"));
    }

    @Test
    void predictRaceTimeRejectsNonPositiveDistance() {
        assertInvalidArgument(tools.predictRaceTime(null, null, 0d, false), "distanceKm");
        assertInvalidArgument(tools.predictRaceTime(null, null, -5d, false), "distanceKm");
        // 不传仍输出四档
        assertEquals("OK", tools.predictRaceTime(null, null, null, false).get("status"));
    }

    @Test
    void dailyRunningRejectsUnknownGroupBy() {
        assertInvalidArgument(tools.getDailyRunning(null, null, "quarter"), "groupBy");
        assertEquals("week", tools.getDailyRunning(null, null, "week").get("groupBy"));
        assertEquals("day", tools.getDailyRunning(null, null, null).get("groupBy"));
    }

    @Test
    void trainingTrendRejectsUnknownPeriod() {
        assertInvalidArgument(tools.getTrainingTrend("quarter", null, null), "period");
        assertEquals("month", tools.getTrainingTrend("month", 2, null).get("period"));
        assertEquals("week", tools.getTrainingTrend(null, 2, null).get("period"));
    }

    /** list_runs 过去有三种不同的处理：sortBy 静默按 date、weekday 越界等于没过滤、min>max 直接抛异常。 */
    @Test
    void listRunsRejectsUnrecognisedFilters() {
        assertInvalidArgument(tools.listRuns(null, null, null, null, null, null, null,
                null, "random", 0, 5), "sortBy");
        assertInvalidArgument(tools.listRuns(null, null, null, null, null, null, null,
                9, null, 0, 5), "weekday");
        assertInvalidArgument(tools.listRuns(null, null, null, 10d, 5d, null, null,
                null, null, 0, 5), "minDistanceKm");
        assertInvalidArgument(tools.listRuns(null, null, null, null, null, 400d, 330d,
                null, null, 0, 5), "minPaceSecondsPerKm");

        // 合法值照常工作，且回显生效值
        Map<String, Object> ok = tools.listRuns(null, null, null, null, null, null, null,
                null, "DISTANCE", 0, 2);
        assertEquals("OK", ok.get("status"));
        assertEquals("distance", ok.get("sortBy"));
    }

    private static void assertInvalidArgument(Map<String, Object> result, String param) {
        assertEquals("INVALID_ARGUMENT", result.get("status"), "应拒绝非法参数而不是静默回落");
        assertEquals(param, result.get("param"), "要指出是哪个参数");
        assertNotNull(result.get("value"), "要回显收到的值");
        assertNotNull(result.get("message"));
    }
}
