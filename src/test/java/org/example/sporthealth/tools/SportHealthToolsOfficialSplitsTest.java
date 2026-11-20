package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.Test;

/**
 * {@code get_official_splits}：华为官方分段（pm2 单公里 / bpm2 单英里）与 GPS 分段的逐段对比。
 *
 * <p>一条 2.5 公里、每 100 米一个点、30 秒一个点的轨迹，GPS 口径的整公里配速≈300 秒/公里；
 * 官方 pm2 故意给 304 / 308 / 310，用来验证差值方向（官方 − GPS）与逐段对齐。
 */
class SportHealthToolsOfficialSplitsTest {

    private static final double MILE_METERS = 1609.344d;

    private final HealthDataProperties properties =
            new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);

    @Test
    void comparesOfficialKilometreSplitsAgainstGps() {
        SportHealthTools tools = tools(
                List.of(run("run-km", 2500d)),
                Map.of("run-km", route(26)),
                Map.of("run-km", List.of(
                        pm2(0, 1000d, 304d),
                        pm2(1, 2000d, 308d),
                        pm2(2, 2500d, 310d))));

        Map<String, Object> result = tools.getOfficialSplits("run-km", null, null, null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("run", result.get("scope"));
        Map<?, ?> km = (Map<?, ?>) ((Map<?, ?>) result.get("units")).get("km");
        assertEquals("HUAWEI_PM2", km.get("source"));
        assertEquals(1000d, km.get("unitMeters"));
        assertEquals(3, km.get("splitCount"));
        assertEquals(2, km.get("fullSplitCount"), "末段 0.5 公里不算整段");
        assertEquals(2.5d, (Double) km.get("coveredKm"), 1e-9);

        List<?> splits = (List<?>) km.get("splits");
        Map<?, ?> first = (Map<?, ?>) splits.get(0);
        assertEquals(304d, first.get("paceSecondsPerUnit"));
        assertEquals(304d, first.get("paceSecondsPerKm"));
        assertEquals("5'04\"/km", first.get("paceText"));
        double gpsPace = (Double) first.get("gpsPaceSecondsPerKm");
        assertEquals(300d, gpsPace, 1d);
        assertEquals(304d - gpsPace, (Double) first.get("deltaSecondsPerKm"), 0.15);
        assertTrue(((String) first.get("deltaText")).startsWith("官方慢"), "正数表示官方更慢");
        assertEquals(304d - gpsPace, (Double) first.get("cumulativeDeltaSeconds"), 0.15);

        Map<?, ?> last = (Map<?, ?>) splits.get(2);
        assertEquals(true, last.get("partial"));
        assertEquals(310d, last.get("paceSecondsPerUnit"), "末段 p 是配速，不是用时");
        assertEquals(310d * 0.5d, (Double) last.get("durationSeconds"), 0.06);

        // 每一段的差值都必须等于「官方 − GPS」，方向不能反
        List<Double> deltas = new ArrayList<>();
        for (Object item : splits) {
            Map<?, ?> row = (Map<?, ?>) item;
            double official = (Double) row.get("paceSecondsPerKm");
            double gps = (Double) row.get("gpsPaceSecondsPerKm");
            assertEquals(official - gps, (Double) row.get("deltaSecondsPerKm"), 0.15);
            deltas.add((Double) row.get("deltaSecondsPerKm"));
        }

        Map<?, ?> comparison = (Map<?, ?>) km.get("comparison");
        assertEquals(splits.size(), comparison.get("comparedSplits"), "三段长度都在 50 米容差内，全部可比");
        double meanAbs = deltas.stream().mapToDouble(Math::abs).average().orElseThrow();
        assertEquals(meanAbs, (Double) comparison.get("meanAbsDeltaSecondsPerKm"), 0.1);
        assertEquals(deltas.stream().mapToDouble(Math::abs).max().orElseThrow(),
                (Double) comparison.get("maxAbsDeltaSecondsPerKm"), 0.1);
        assertEquals("官方整体比 GPS 慢", comparison.get("direction"));
        assertTrue(((String) comparison.get("verdict")).contains("配速"), "结论要能直接复述给别人");

        Map<?, ?> fastest = (Map<?, ?>) km.get("fastestComparison");
        assertNotNull(fastest);
        assertEquals(304d, (Double) ((Map<?, ?>) fastest.get("official")).get("paceSecondsPerUnit"));
        assertEquals(304d - gpsPace, (Double) fastest.get("deltaSecondsPerKm"), 0.2);
    }

    /** {@code bpm2} 是英制单英里分段：{@code d} 是千分之一英里，{@code p} 是秒/英里。 */
    @Test
    void mileUnitReadsBritishPaceMap() {
        SportHealthTools tools = tools(
                List.of(run("run-mile", 3200d)),
                Map.of("run-mile", route(33)),
                Map.of("run-mile", List.of(
                        // 1 英里 ≈ 1609.344 米、2 英里 ≈ 3218.7 米，末段不足一英里
                        bpm2(0, 1000d, 500d),
                        bpm2(1, 2000d, 520d))));

        Map<String, Object> result = tools.getOfficialSplits("run-mile", null, null, "mile", null, null);

        Map<?, ?> mile = (Map<?, ?>) ((Map<?, ?>) result.get("units")).get("mile");
        assertEquals("HUAWEI_BPM2", mile.get("source"));
        assertEquals(MILE_METERS, (Double) mile.get("unitMeters"), 1e-9);
        assertEquals(2, mile.get("splitCount"));
        assertEquals(3.2187d, (Double) mile.get("coveredKm"), 0.002);

        Map<?, ?> first = (Map<?, ?>) ((List<?>) mile.get("splits")).get(0);
        assertEquals(500d, first.get("paceSecondsPerUnit"));
        assertEquals("8'20\"/mi", first.get("paceText"), "配额必须带 mi 后缀，否则会被读成配速崩坏");
        assertEquals(500d * 1000d / MILE_METERS, (Double) first.get("paceSecondsPerKm"), 0.1);
        assertEquals("5'11\"/km", first.get("paceTextPerKm"));
        assertEquals(false, first.get("partial"));

        assertEquals(510d, (Double) mile.get("avgPaceSecondsPerUnit"), 0.5);
        assertEquals("8'30\"/mi", mile.get("avgPaceText"));
    }

    /** 规范类型缺失时退回扁平写法 {@code p-m} / {@code b-p-m}（k = 距离×10000 + 点序号）。 */
    @Test
    void fallsBackToFlatStreamTypes() {
        SportHealthTools tools = tools(
                List.of(run("run-flat", 2000d)),
                Map.of("run-flat", route(21)),
                Map.of("run-flat", List.of(
                        flat("p-m", 0, 1000d, 305d, 168),
                        flat("p-m", 1, 2000d, 315d, 351))));

        Map<String, Object> result = tools.getOfficialSplits("run-flat", null, null, "km", null, null);

        Map<?, ?> km = (Map<?, ?>) ((Map<?, ?>) result.get("units")).get("km");
        assertEquals("HUAWEI_PM2", km.get("source"));
        List<?> splits = (List<?>) km.get("splits");
        assertEquals(2, splits.size());
        Map<?, ?> first = (Map<?, ?>) splits.get(0);
        assertEquals(305d, first.get("paceSecondsPerUnit"));
        assertEquals(168, first.get("pointIndex"), "扁平写法的 k 要拆成距离 + 点序号");
    }

    /** {@code unit=both} 两套口径并列，缺哪套不影响另一套。 */
    @Test
    void bothUnitsReturnedSideBySide() {
        Map<String, List<TrackStream>> streams = new LinkedHashMap<>();
        streams.put("run-both", List.of(pm2(0, 1000d, 304d), bpm2(0, 1000d, 500d)));
        SportHealthTools tools = tools(List.of(run("run-both", 1609.344d)),
                Map.of("run-both", route(17)), streams);

        Map<String, Object> result = tools.getOfficialSplits("run-both", null, null, "both", null, null);

        Map<?, ?> units = (Map<?, ?>) result.get("units");
        assertEquals(2, units.size());
        assertNotNull(units.get("km"));
        assertNotNull(units.get("mile"));
    }

    /** 区间汇总只统计有官方分段的记录，覆盖率要显式给出，否则会被读成「全都差」或「全都不差」。 */
    @Test
    void rangeSummaryCountsCoverage() {
        SportHealthTools tools = tools(
                List.of(run("run-a", 2500d), run("run-b", 2500d)),
                Map.of("run-a", route(26), "run-b", route(26)),
                Map.of("run-a", List.of(pm2(0, 1000d, 304d), pm2(1, 2000d, 308d), pm2(2, 2500d, 310d))));

        Map<String, Object> result = tools.getOfficialSplits(null, null, null, "km", 0, 10);

        assertEquals("OK", result.get("status"));
        assertEquals("range", result.get("scope"));
        assertEquals(2, result.get("totalRuns"));
        Map<?, ?> coverage = (Map<?, ?>) result.get("coverage");
        assertEquals(1, coverage.get("officialRuns"));
        assertEquals(1, coverage.get("comparedRuns"));
        assertEquals(50d, (Double) coverage.get("officialPercent"), 1e-9);

        // 区间里的均值/中位数都是「各次平均绝对差」的统计，应当与单次结论严格一致
        double singleMean = (Double) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) tools
                .getOfficialSplits("run-a", null, null, "km", null, null)
                .get("units")).get("km")).get("comparison")).get("meanAbsDeltaSecondsPerKm");
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(singleMean, (Double) summary.get("meanAbsDeltaSecondsPerKm"), 0.05);
        assertEquals(singleMean, (Double) summary.get("medianAbsDeltaSecondsPerKm"), 0.05);
        List<?> rows = (List<?>) result.get("runs");
        assertEquals(1, rows.size(), "只有带官方分段的记录进列表，没有的靠 coverage 体现");
        assertEquals(1, result.get("total"));
        Map<?, ?> only = (Map<?, ?>) rows.get(0);
        assertEquals("run-a", only.get("runId"));
        assertEquals(3, only.get("comparedSplits"));
        assertNotNull(only.get("gpsAvgPaceSecondsPerKm"));
    }

    /** 没有任何官方分段时返回 NO_DATA，并指路到 GPS 口径的工具。 */
    @Test
    void reportsNoDataWhenOfficialStreamsAreMissing() {
        SportHealthTools tools = tools(List.of(run("run-none", 2500d)),
                Map.of("run-none", route(26)), Map.of());

        Map<String, Object> result = tools.getOfficialSplits("run-none", null, null, null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertTrue(((String) result.get("detail")).contains("get_run_splits"));
    }

    @Test
    void rejectsIllegalUnitAndMixedScope() {
        SportHealthTools tools = tools(List.of(run("run-km", 2500d)),
                Map.of("run-km", route(26)),
                Map.of("run-km", List.of(pm2(0, 1000d, 304d))));

        Map<String, Object> badUnit = tools.getOfficialSplits("run-km", null, null, "furlong", null, null);
        assertEquals("INVALID_ARGUMENT", badUnit.get("status"));
        assertEquals("unit", badUnit.get("param"));
        assertNotNull(badUnit.get("allowedValues"));

        Map<String, Object> mixed = tools.getOfficialSplits("run-km", "2026-10-01", null, null, null, null);
        assertEquals("INVALID_ARGUMENT", mixed.get("status"));
        assertEquals("runId", mixed.get("param"));

        Map<String, Object> bothInRange = tools.getOfficialSplits(null, null, null, "both", null, null);
        assertEquals("INVALID_ARGUMENT", bothInRange.get("status"));
        assertEquals("unit", bothInRange.get("param"));

        assertEquals("NOT_FOUND", tools.getOfficialSplits("run-unknown", null, null, null, null, null).get("status"));
    }

    // ---------------------------------------------------------------- 构造数据

    private SportHealthTools tools(List<RunActivity> runs, Map<String, List<TrackPoint>> routes,
                                   Map<String, List<TrackStream>> streams) {
        RunDataset dataset = new RunDataset(Instant.parse("2026-11-03T12:00:00Z"), "memory",
                List.of(), runs, List.of());
        HealthDataRepository fake = new HealthDataRepository() {
            @Override
            public RunDataset dataset() {
                return dataset;
            }

            @Override
            public void reload() {
                // 内存实现，无需重新扫描
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return routes.getOrDefault(runId, List.of());
            }

            @Override
            public List<TrackStream> streams(String runId, String streamType) {
                return streams.getOrDefault(runId, List.of()).stream()
                        .filter(stream -> streamType == null || streamType.equals(stream.streamType()))
                        .toList();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService service = new RunAnalyticsService(fake, new DateRangeResolver(clock));
        return new SportHealthTools(service, fake, properties, null);
    }

    /** 一条南北向直线：{@code count} 个点、每点间隔 100 米、30 秒。 */
    private static List<TrackPoint> route(int count) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 6, 56, 16);
        List<TrackPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new TrackPoint(start.plusSeconds((long) i * 30),
                    39.9 + i * 0.000898, 116.4, 50d));
        }
        return points;
    }

    private static RunActivity run(String id, double distanceMeters) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 6, 56, 16);
        double durationSeconds = distanceMeters / 1000d * 300d;
        return new RunActivity(id, "act-" + id, "户外跑步", start.toLocalDate(), start,
                start.plusSeconds((long) durationSeconds),
                Instant.parse("2026-10-24T22:56:16Z"), durationSeconds, distanceMeters, 30d, 28d,
                "2", "OUTDOOR", 0, 12d, 300d, 39.9057, 116.3972, 39.906, 116.398,
                50d, 70d, id + ".json", "HUAWEI_HEALTH");
    }

    /** pm2：{@code d} 是累计米数、{@code p} 是秒/公里。 */
    private static TrackStream pm2(int seq, double endMeters, double paceSeconds) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("tp", "pm2");
        fields.put("d", String.valueOf((long) endMeters));
        fields.put("n", String.valueOf(seq + 1));
        fields.put("p", String.valueOf(paceSeconds));
        return new TrackStream("run", "pm2", seq, null, seq + 1, paceSeconds, fields);
    }

    /** bpm2：{@code d} 是千分之一英里、{@code p} 是秒/英里。 */
    private static TrackStream bpm2(int seq, double endThousandthsOfMile, double paceSecondsPerMile) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("tp", "bpm2");
        fields.put("d", String.valueOf((long) endThousandthsOfMile));
        fields.put("n", String.valueOf(seq + 1));
        fields.put("p", String.valueOf(paceSecondsPerMile));
        return new TrackStream("run", "bpm2", seq, null, seq + 1, paceSecondsPerMile, fields);
    }

    /** 扁平写法：{@code k = 距离（千分之一单位）× 10000 + 点序号}，配速在 {@code v}。 */
    private static TrackStream flat(String type, int seq, double endUnits, double pace, int pointIndex) {
        long key = (long) endUnits * 10000L + pointIndex;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("tp", type);
        fields.put("v", String.valueOf(pace));
        fields.put("k", String.valueOf(key));
        return new TrackStream("run", type, seq, null, (int) key, pace, fields);
    }
}
