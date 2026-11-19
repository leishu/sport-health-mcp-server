package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.DynamicsSegment;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code get_running_dynamics} 的返回结构与参数口径。
 *
 * <p>核心是三条：区间汇总要显式给出「多少次跑步真的带跑姿数据」，单次详情要能给分段与前后半程，
 * 以及参数组合不能悄悄错位（runId 和日期互斥、splitKm 只在单次模式、groupBy 只在区间模式）。
 */
class SportHealthToolsRunningDynamicsTest {

    private static final String WITH_POSTURE = "hw-20260613-0621-317";
    private static final String STRIDE_ONLY = "hw-20260701-0519-1845";
    private static final String NO_METRICS = "20251115户外跑步";

    private SportHealthTools tools;

    @BeforeEach
    void setUp() {
        tools = new SportHealthTools(analytics(), repository(),
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200), null);
    }

    @Test
    void rangeSummaryReportsCoverageAndAverages() {
        Map<String, Object> result = tools.getRunningDynamics(null, null, null, null, null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("range", result.get("scope"));
        assertEquals(3, result.get("totalRuns"), "区间内三次记录，其中一次没有跑姿也没有步数");

        Map<?, ?> coverage = (Map<?, ?>) result.get("coverage");
        assertEquals(3, coverage.get("runs"));
        assertEquals(1, coverage.get("dynamicsRuns"));
        assertEquals(2, coverage.get("strideRuns"));
        assertEquals(33.3, (Double) coverage.get("dynamicsPercent"), 1e-9);
        assertTrue(String.valueOf(coverage.get("hint")).contains("1 次带跑姿数据"));

        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(2, summary.get("runCount"), "有步幅的那条也计入 runCount");
        assertEquals(1, summary.get("postureRunCount"));
        assertEquals(2, summary.get("strideRunCount"));
        Map<?, ?> gct = (Map<?, ?>) summary.get("groundContactTimeMs");
        assertEquals(1, gct.get("count"));
        assertEquals(280.0, (Double) gct.get("average"), 1e-9);
        Map<?, ?> stride = (Map<?, ?>) summary.get("strideLengthMeters");
        assertEquals(2, stride.get("count"));
        assertEquals(1.0, (Double) stride.get("average"), 1e-9);
        assertTrue(summary.containsKey("verticalOscillationCm"), "带跑姿的那条有垂直振幅");
        assertEquals(9.2, (Double) ((Map<?, ?>) summary.get("verticalOscillationCm")).get("average"), 1e-9);

        assertEquals("month", result.get("groupBy"));
        List<?> trend = (List<?>) result.get("trend");
        assertEquals(2, trend.size(), "2026-06 与 2026-07 各一条");

        List<?> runs = (List<?>) result.get("runs");
        assertEquals(2, runs.size(), "没有跑姿也没有步数的那条不进列表");
        assertEquals(STRIDE_ONLY, ((Map<?, ?>) runs.get(0)).get("runId"), "新的在前");
        assertEquals(WITH_POSTURE, ((Map<?, ?>) runs.get(1)).get("runId"));
        assertFalse(((Map<?, ?>) runs.get(0)).containsKey("groundContactTimeMs"), "缺的字段不占位");
        assertEquals(1.0, (Double) ((Map<?, ?>) runs.get(0)).get("strideLengthMeters"), 1e-9);

        assertEquals(2, result.get("total"));
        assertEquals(Boolean.FALSE, result.get("hasMore"));
        assertNotNull(result.get("metrics"));
        assertNotNull(result.get("notes"));
    }

    @Test
    void rangeSupportsPagingAndTrendGranularity() {
        Map<String, Object> page = tools.getRunningDynamics(null, null, null, "week", null, 0, 1);

        assertEquals(1, ((List<?>) page.get("runs")).size());
        assertEquals(Boolean.TRUE, page.get("hasMore"));
        assertEquals(2, page.get("total"));
        assertEquals("week", page.get("groupBy"));
        assertEquals(2, ((List<?>) page.get("trend")).size());

        Map<String, Object> noTrend = tools.getRunningDynamics(null, null, null, "none", null, null, null);
        assertFalse(noTrend.containsKey("trend"), "groupBy=none 时不出趋势");
        assertFalse(noTrend.containsKey("groupBy"));
    }

    @Test
    void singleRunReturnsSegmentsAndHalves() {
        Map<String, Object> result = tools.getRunningDynamics(WITH_POSTURE, null, null, null, 0.5, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("run", result.get("scope"));
        assertEquals(WITH_POSTURE, result.get("runId"));
        assertEquals(500.0, result.get("splitMeters"));

        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(280.0, (Double) summary.get("groundContactTimeMs"), 1e-9);
        assertEquals(49, summary.get("sampleCount"), "每 5 秒一个采样，250 秒共 49 个");

        List<?> segments = (List<?>) result.get("segments");
        assertEquals(2, segments.size());
        DynamicsSegment first = (DynamicsSegment) segments.get(0);
        assertEquals(0.5, first.toKm(), 1e-9);
        assertEquals(280.0, first.groundContactTimeMs(), 1e-9);
        assertEquals(1.333, first.strideLengthMeters(), 0.002);

        Map<?, ?> halves = (Map<?, ?>) result.get("halves");
        assertNotNull(halves.get("first"));
        assertNotNull(halves.get("second"));
        Map<?, ?> delta = (Map<?, ?>) halves.get("delta");
        assertEquals(10.0, (Double) delta.get("groundContactTimeMs"), 1e-9, "后半程 gct 大 10 ms");
    }

    @Test
    void singleRunWithoutPostureStillReportsStride() {
        Map<String, Object> result = tools.getRunningDynamics(STRIDE_ONLY, null, null, null, null, null, null);

        assertEquals("OK", result.get("status"));
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(1.0, (Double) summary.get("strideLengthMeters"), 1e-9);
        assertFalse(summary.containsKey("groundContactTimeMs"));
        assertNotNull(result.get("message"), "没有跑姿时必须说明为什么");
    }

    @Test
    void recordWithoutAnyMetricReturnsNoData() {
        Map<String, Object> result = tools.getRunningDynamics(NO_METRICS, null, null, null, null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertTrue(String.valueOf(result.get("detail")).contains("跑姿"),
                "noData 的 message 是通用文案，具体原因在 detail 里: " + result);
    }

    @Test
    void unknownRunIdReturnsNotFound() {
        assertEquals("NOT_FOUND", tools.getRunningDynamics("nope", null, null, null, null, null, null).get("status"));
    }

    @Test
    void mismatchedArgumentCombinationsAreRejected() {
        assertInvalidArgument(tools.getRunningDynamics(WITH_POSTURE, "2026-01-01", null, null, null, null, null),
                "runId");
        assertInvalidArgument(tools.getRunningDynamics(WITH_POSTURE, null, null, "month", null, null, null),
                "groupBy");
        assertInvalidArgument(tools.getRunningDynamics(null, null, null, null, 1d, null, null), "splitKm");
        assertInvalidArgument(tools.getRunningDynamics(null, null, null, "quarter", null, null, null), "groupBy");
        assertInvalidArgument(tools.getRunningDynamics(WITH_POSTURE, null, null, null, 0.01, null, null), "splitKm");
        assertInvalidArgument(tools.getRunningDynamics(WITH_POSTURE, null, null, null, -1d, null, null), "splitKm");
    }

    @Test
    void emptyRangeReturnsNoData() {
        Map<String, Object> result = tools.getRunningDynamics(null, "2020-01-01", "2020-01-31", null, null, null, null);

        assertEquals("NO_DATA", result.get("status"));
    }

    private static void assertInvalidArgument(Map<String, Object> result, String param) {
        assertEquals("INVALID_ARGUMENT", result.get("status"), String.valueOf(result));
        assertEquals(param, result.get("param"));
    }

    // ---------------------------------------------------------------- 测试数据

    private static RunDataset dataset() {
        return new RunDataset(Instant.parse("2026-11-19T07:00:00Z"), "memory", List.of(),
                List.of(run(WITH_POSTURE, LocalDate.of(2026, 6, 13), 1000,
                                "{\"sportType\":258,\"totalTime\":250000,\"totalDistance\":1000,"
                                        + "\"avgStepRate\":180,\"totalSteps\":1000,\"avgPace\":250.0,"
                                        + "\"mAvgGroundContactTime\":280,\"mExtendTrackDataMap\":"
                                        + "{\"avg_v_osc\":\"9.2\",\"avg_gc_tb\":\"49.6\"}}", 1000),
                        run(STRIDE_ONLY, LocalDate.of(2026, 7, 1), 5000,
                                "{\"sportType\":258,\"totalTime\":1500000,\"totalDistance\":5000,"
                                        + "\"avgStepRate\":176,\"totalSteps\":5000,\"avgPace\":300.0}", 5000),
                        run(NO_METRICS, LocalDate.of(2025, 11, 15), 0, null, 5000)),
                List.of());
    }

    private static RunActivity run(String id, LocalDate date, int steps, String summary, int distanceMeters) {
        boolean tcx = summary == null;
        return new RunActivity(id, id + "Z", "户外跑步", date,
                date.atTime(6, 0), date.atTime(6, 30), date.atTime(6, 0).toInstant(java.time.ZoneOffset.UTC),
                tcx ? 1800d : distanceMeters / 4d, (double) distanceMeters, 0d, 0d, "0", "OUTDOOR", 26, 14.4, 250d,
                39.9, 116.4, 39.9, 116.4, 50d, 50d, id + ".json",
                tcx ? "TCX" : "HUAWEI_HEALTH",
                tcx ? null : 140, tcx ? null : 150, null, tcx ? null : 180,
                tcx ? null : steps, tcx ? null : 60d, tcx ? null : "1", tcx ? null : 258,
                tcx ? null : 4, summary);
    }

    private static HealthDataRepository repository() {
        return new HealthDataRepository() {
            @Override
            public RunDataset dataset() {
                return SportHealthToolsRunningDynamicsTest.dataset();
            }

            @Override
            public void reload() {
                // 内存实现
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return WITH_POSTURE.equals(runId) ? routePoints() : List.of();
            }

            @Override
            public List<TrackStream> streams(String runId, String streamType) {
                if (!WITH_POSTURE.equals(runId)) {
                    return List.of();
                }
                return rpRows().stream()
                        .filter(row -> streamType == null || streamType.equals(row.streamType()))
                        .toList();
            }
        };
    }

    private static RunAnalyticsService analytics() {
        HealthDataRepository repository = repository();
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T07:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new RunAnalyticsService(repository, new DateRangeResolver(clock));
    }

    /** 1000 米、250 秒、每 10 秒一个点（40 米），步频 180。 */
    private static List<TrackPoint> routePoints() {
        List<TrackPoint> points = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 6, 13, 6, 21);
        for (int i = 0; i <= 25; i++) {
            double meters = i * 40d;
            points.add(new TrackPoint(start.plusSeconds(i * 10L), 39.9 + meters / 111_320d, 116.4,
                    65d, 140, 180, null));
        }
        return points;
    }

    /** 每 5 秒一个跑姿采样：前半程 280、后半程 290。 */
    private static List<TrackStream> rpRows() {
        List<TrackStream> rows = new ArrayList<>();
        int seq = 0;
        for (int k = 7; k < 250; k += 5) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("tp", "rp");
            fields.put("k", String.valueOf(k));
            fields.put("gct", k < 125 ? "280" : "290");
            fields.put("vo", "9.2");
            fields.put("gtb", "49.6");
            rows.add(new TrackStream(WITH_POSTURE, "rp", seq++, null, k, null, fields));
        }
        return rows;
    }
}
