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
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.RunSplit;
import org.example.sporthealth.domain.SplitCrossCheck;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.domain.VendorSplit;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 工具层返回结构测试：quality 块与个人最佳的新类别、轨迹点的传感器字段。
 */
class SportHealthToolsTest {

    private SportHealthTools tools;
    private HealthDataProperties properties;
    private RunAnalyticsService analytics;

    @BeforeEach
    void setUp() {
        properties = new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
        // 天气统计只在 JDBC 仓储下有意义；内存仓储传 null，storage 里就不出现 weather 字段
        tools = new SportHealthTools(analytics, repository, properties, null);
    }

    @Test
    void reportsQualityWithWarningCategoriesAndAnomalyChecks() {
        Map<String, Object> result = tools.describeDataSource();
        assertEquals("OK", result.get("status"));

        Map<?, ?> quality = (Map<?, ?>) result.get("quality");
        assertTrue(((Map<?, ?>) quality.get("warningCategories")).isEmpty(), "样本数据没有解析告警");
        assertTrue(((List<?>) quality.get("paceOutliers")).isEmpty(), "5 次跑步配速接近，不应有离群");
        assertTrue(((List<?>) quality.get("duplicateTimestampRunIds")).isEmpty(), "样本轨迹时间戳唯一");
        assertEquals(Boolean.TRUE, quality.get("clean"));
    }

    @Test
    void exposesBest1kAndBest5kCategories() {
        Map<String, Object> all = tools.getPersonalBests(null, null, "all", null);
        Map<?, ?> allBests = (Map<?, ?>) all.get("bests");
        assertNotNull(allBests.get("best1k"));
        assertNotNull(allBests.get("best5k"));

        Map<String, Object> only5k = tools.getPersonalBests(null, null, "5k", null);
        assertEquals("best5k", only5k.get("category"));
        Map<?, ?> only5kBests = (Map<?, ?>) only5k.get("bests");
        assertEquals(1, only5kBests.size());
        assertNotNull(only5kBests.get("best5k"));
    }

    /** 任意目标距离：样本最长 10.7 公里，所以 10 公里能算出来，半马列在不可用里。 */
    @Test
    void personalBestsAcceptArbitraryDistances() {
        Map<String, Object> result = tools.getPersonalBests(null, null, "all", List.of(1d, 5d, 10d, 21.0975d));

        Map<?, ?> bests = (Map<?, ?>) result.get("bests");
        assertNotNull(bests.get("best1k"));
        assertNotNull(bests.get("best5k"));
        assertNotNull(bests.get("best10k"), "样本有 10.7 公里的一次，10K 必须算得出来");
        assertNull(bests.get("bestHalf"));
        assertEquals(List.of(1d, 5d, 10d, 21.0975d), result.get("effortDistancesKm"));
        assertEquals(List.of(21.0975d), result.get("unavailableDistancesKm"));
    }

    /** 只查配速时不该顺手把轨迹算一遍，返回体里也不该出现最佳努力字段。 */
    @Test
    void nonDistanceCategorySkipsEffortScan() {
        Map<String, Object> paceOnly = tools.getPersonalBests(null, null, "pace", null);

        assertFalse(paceOnly.containsKey("effortDistancesKm"));
        assertFalse(((Map<?, ?>) paceOnly.get("bests")).containsKey("best1k"));
    }

    /** 距离类别的别名都归一到同一个键，半马/全马用固定名。 */
    @Test
    void effortLabelsCoverNamedDistances() {
        assertEquals("best1k", SportHealthTools.effortLabel(1000d));
        assertEquals("best10k", SportHealthTools.effortLabel(10000d));
        assertEquals("bestHalf", SportHealthTools.effortLabel(21097.5d));
        assertEquals("bestMarathon", SportHealthTools.effortLabel(42195d));
        assertEquals("best2.5k", SportHealthTools.effortLabel(2500d));
        assertEquals("bestMarathon", tools.getPersonalBests(null, null, "full", null).get("category"));
        assertEquals("bestHalf", tools.getPersonalBests(null, null, "半马", null).get("category"));
    }

    @Test
    void describeDataSourceNamesCountingScopesExplicitly() {
        Map<String, Object> result = tools.describeDataSource();

        assertEquals(5, result.get("runningCount"), "只统计 RUN 活动的条数");
        assertEquals(5, result.get("runSourceFileCount"), "来源文件是去重后的文件名个数，样本里 5 个文件各一条");
        assertEquals(result.get("runSourceFileCount"), ((List<?>) result.get("runSourceFiles")).size());

        // 旧名字最容易让人把「RUN 条数」当成「全部运动」或「磁盘文件数」，必须彻底消失
        assertFalse(result.containsKey("runCount"));
        assertFalse(result.containsKey("fileCount"));
        assertFalse(result.containsKey("files"));
        // 内存仓储没有华为导出统计，allWorkoutCount 明确缺席而不是填 0
        assertFalse(result.containsKey("allWorkoutCount"));
    }

    @Test
    void listRowsAlwaysCarryHuaweiFieldsEvenForTcx() {
        Map<String, Object> result = tools.listRuns(null, null, null, null, null, null, null,
                null, null, 0, 1);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("runs")).get(0);

        // TCX 样本没有华为字段：键必须在，值为 null，而不是整块消失
        for (String key : List.of("avgHeartRate", "maxHeartRate", "minHeartRate", "avgCadence",
                "totalSteps", "totalCaloriesKcal", "recordId", "sportTypeCode", "subType")) {
            assertTrue(row.containsKey(key), "列表行缺少字段 " + key);
            org.junit.jupiter.api.Assertions.assertNull(row.get(key), "TCX 记录的 " + key + " 应为 null");
        }
        // summaryData 约 2.5 KB，列表与详情都不再返回原文，只给一个指向专用工具的指针
        assertFalse(row.containsKey("summaryData"));

        Map<?, ?> detail = (Map<?, ?>) tools.getRunDetail((String) row.get("id"), false, null, null)
                .get("activity");
        Map<?, ?> summaryData = (Map<?, ?>) detail.get("summaryData");
        assertNotNull(summaryData, "详情要说明 summaryData 有没有");
        assertEquals(Boolean.FALSE, summaryData.get("available"), "TCX 记录没有 summaryData");
        assertNotNull(summaryData.get("reason"));
    }

    /** compare_runs 的分段必须和 get_run_splits 用同一把尺子：splitKm 生效、回显 splitMeters、非法值回落 1 公里。 */
    @Test
    void compareRunsHonoursSplitKm() {
        Map<String, Object> list = tools.listRuns(null, null, null, null, null, null, null,
                null, null, 0, 2);
        List<?> runs = (List<?>) list.get("runs");
        String baseId = (String) ((Map<?, ?>) runs.get(0)).get("id");
        String otherId = (String) ((Map<?, ?>) runs.get(1)).get("id");

        Map<?, ?> defaultSplits = (Map<?, ?>) tools.compareRuns(baseId, otherId, true, null).get("splits");
        assertEquals(1000d, splitMetersOf(defaultSplits), "不传 splitKm 时按 1 公里切");
        List<?> oneKm = (List<?>) defaultSplits.get("base");
        assertFalse(oneKm.isEmpty(), "TCX 样本有 GPS 轨迹");

        Map<?, ?> fiveSplits = (Map<?, ?>) tools.compareRuns(baseId, otherId, true, 5d).get("splits");
        assertEquals(5000d, splitMetersOf(fiveSplits));
        List<?> fiveKm = (List<?>) fiveSplits.get("base");
        assertFalse(fiveKm.isEmpty());
        assertTrue(fiveKm.size() < oneKm.size(), "5 公里分段数应少于 1 公里");
        assertEquals(5d, ((RunSplit) fiveKm.get(0)).distanceKm(), 1e-9);

        // 0 / 负数等于没传，不能抛异常也不能切出 0 长度分段
        assertEquals(1000d, splitMetersOf((Map<?, ?>) tools.compareRuns(baseId, otherId, true, 0d).get("splits")));
        assertEquals(1000d, splitMetersOf((Map<?, ?>) tools.compareRuns(baseId, otherId, true, -2d).get("splits")));

        // includeSplits=false 时即使传了 splitKm 也不该顺手把轨迹算一遍
        assertFalse(tools.compareRuns(baseId, otherId, false, 5d).containsKey("splits"));
    }

    private static double splitMetersOf(Map<?, ?> splits) {
        return ((Number) splits.get("splitMeters")).doubleValue();
    }

    @Test
    void formatsReturnedTimesAsSqlStyleStrings() {
        Map<String, Object> list = tools.listRuns(null, null, null, null, null, null, null,
                null, null, 0, 5);
        for (Object item : (List<?>) list.get("runs")) {
            assertTimeText(((Map<?, ?>) item).get("startTime"));
        }

        Map<String, Object> detail = tools.getRunDetail("20261025户外跑步", false, null, null);
        Map<?, ?> activity = (Map<?, ?>) detail.get("activity");
        assertEquals("2026-10-25 06:56:16", activity.get("startTime"), "本地时间保持原时区");
        assertEquals("2026-10-24 22:56:16", activity.get("startTimeUtc"), "UTC 时间按 UTC 格式化");
        assertTimeText(activity.get("endTime"));

        Map<String, Object> route = tools.getRunRoute("20261025户外跑步", 0, 1, null, null, null);
        assertTimeText(((Map<?, ?>) ((List<?>) route.get("points")).get(0)).get("timestamp"));

        assertTimeText(tools.describeDataSource().get("loadedAt"));
    }

    /**
     * 逐点传感器要真的出现在轨迹点里，且 {@code includeSensors=false} 时整体消失。
     *
     * <p>用 TcxRunRepository 包一层假仓储，只替换 {@code route()}，这样既跑了真实的工具层映射，
     * 又能拿到有心率/步频的轨迹点。
     */
    @Test
    void routePointsCarryPerPointSensorsUnlessDisabled() {
        SportHealthTools sensorTools = toolsWithRoute(List.of(
                new TrackPoint(LocalDateTime.of(2026, 10, 25, 6, 56, 20), 39.9057, 116.3972, 45.123, 128, 180, 1000.456),
                new TrackPoint(LocalDateTime.of(2026, 10, 25, 6, 56, 25), 39.9058, 116.3973, 46.0, 132, 182, 1015.5)));

        Map<String, Object> withSensors = sensorTools.getRunRoute("20261025户外跑步", 0, 2, null, null, null);
        List<?> points = (List<?>) withSensors.get("points");
        Map<?, ?> first = (Map<?, ?>) points.get(0);
        assertEquals(128, first.get("heartRate"));
        assertEquals(180, first.get("cadence"));
        assertEquals(1000.46, ((Number) first.get("distanceMeters")).doubleValue(), 1e-9, "累计距离保留 2 位小数");
        assertEquals(132, ((Map<?, ?>) points.get(1)).get("heartRate"));

        Map<String, Object> positional = sensorTools.getRunRoute("20261025户外跑步", 0, 2, null, null, false);
        Map<?, ?> stripped = (Map<?, ?>) ((List<?>) positional.get("points")).get(0);
        assertFalse(stripped.containsKey("heartRate"), "includeSensors=false 时不返回心率");
        assertFalse(stripped.containsKey("cadence"));
        assertFalse(stripped.containsKey("distanceMeters"));
        assertTrue(stripped.containsKey("latitude"), "位置字段照旧返回");
    }

    /** GeoJSON 只有坐标，传感器放在与坐标等长、同序的平行数组里。 */
    @Test
    void geojsonSensorsAlignWithCoordinates() {
        SportHealthTools sensorTools = toolsWithRoute(List.of(
                new TrackPoint(LocalDateTime.of(2026, 10, 25, 6, 56, 20), 39.9057, 116.3972, 45.0, 128, 180, 1000.0),
                // (0,0) 视为无效坐标，会被坐标数组过滤掉，传感器数组必须同步过滤
                new TrackPoint(LocalDateTime.of(2026, 10, 25, 6, 56, 21), 0d, 0d, null, 129, 181, 1004.0),
                new TrackPoint(LocalDateTime.of(2026, 10, 25, 6, 56, 25), 39.9058, 116.3973, 46.0, 132, 182, 1015.5)));

        Map<String, Object> geo = sensorTools.getRunRoute("20261025户外跑步", null, null, "geojson", null, null);

        List<?> coordinates = (List<?>) ((Map<?, ?>) geo.get("geometry")).get("coordinates");
        Map<?, ?> sensors = (Map<?, ?>) geo.get("sensors");
        assertEquals(2, coordinates.size(), "无效坐标被过滤");
        assertEquals(coordinates.size(), ((List<?>) sensors.get("heartRate")).size(), "传感器数组与坐标等长");
        assertEquals(coordinates.size(), ((List<?>) sensors.get("cadence")).size());
        assertEquals(coordinates.size(), ((List<?>) sensors.get("distanceMeters")).size());
        assertEquals(List.of(128, 132), sensors.get("heartRate"), "过滤后仍与坐标同序");
        assertEquals("geometry.coordinates", sensors.get("alignedWith"));

        Map<String, Object> noSensors = sensorTools.getRunRoute("20261025户外跑步", null, null, "geojson", null, false);
        assertFalse(noSensors.containsKey("sensors"), "关闭后不返回 sensors 块");
    }

    /** TCX 来源没有传感器，键仍然存在（值为 null），避免消费方要写两套判空逻辑。 */
    @Test
    void tcxRoutePointsKeepSensorKeysAsNull() {
        Map<String, Object> route = tools.getRunRoute("20261025户外跑步", 0, 1, null, null, null);
        Map<?, ?> point = (Map<?, ?>) ((List<?>) route.get("points")).get(0);
        assertTrue(point.containsKey("heartRate"));
        assertNull(point.get("heartRate"));
        assertNull(point.get("cadence"));
        assertNull(point.get("distanceMeters"));
    }

    /**
     * 华为来源的官方分段（pm2）要和 GPS 分段一起给出，并附交叉校验。
     *
     * <p>构造一条 2 公里、每 100 米一个点、30 秒一个点的轨迹（≈300 秒/公里），
     * 华为给的分段配速故意各慢 4~8 秒，用来验证差值被算出来。
     */
    @Test
    void runSplitsIncludeVendorPacesAndCrossCheck() {
        List<TrackPoint> route = syntheticRoute(21, 30);
        RunActivity run = sampleRun("run-vendor", "act-vendor", 50d, 300d);
        List<TrackStream> streams = List.of(
                stream("pm2", 0, 1000d, 304d),
                stream("pm2", 1, 2000d, 308d),
                stream("pm2", 2, 2500d, 310d));
        SportHealthTools vendorTools = toolsWithStreams(run, route, streams);

        Map<String, Object> result = vendorTools.getRunSplits("run-vendor", null);

        Map<?, ?> vendor = (Map<?, ?>) result.get("vendorSplits");
        assertNotNull(vendor, "有 pm2 时必须给出厂商分段");
        assertEquals("HUAWEI_PM2", vendor.get("source"));
        List<?> vendorSplits = (List<?>) vendor.get("splits");
        assertEquals(3, vendorSplits.size(), "末段不足一公里也要保留");
        VendorSplit lastSplit = (VendorSplit) vendorSplits.get(2);
        assertTrue(lastSplit.partial());
        assertEquals(0.5d, lastSplit.distanceKm(), 1e-9);

        SplitCrossCheck check = (SplitCrossCheck) result.get("splitCrossCheck");
        assertNotNull(check, "段长一致的两份分段应能交叉校验");
        assertEquals(2, check.comparedSplits());
        assertTrue(check.meanAbsDeltaSecondsPerKm() > 0d);
        assertTrue(check.maxAbsDeltaSecondsPerKm() >= 4d);
    }

    /** 一条南北向的直线轨迹：{@code count} 个点、每段 100 米、{@code secondsPerPoint} 秒一个点。 */
    private static List<TrackPoint> syntheticRoute(int count, int secondsPerPoint) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 6, 56, 16);
        List<TrackPoint> points = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new TrackPoint(start.plusSeconds((long) i * secondsPerPoint),
                    39.9 + i * 0.000898, 116.4, 50d));
        }
        return points;
    }

    /** 一条 pm2 流：{@code d} 是累计距离（米），{@code p} 是该段配速（秒/公里）。 */
    private static TrackStream stream(String type, int seq, double endMeters, double paceSeconds) {
        Map<String, String> fields = Map.of(
                "tp", type,
                "d", String.valueOf((long) endMeters),
                "n", String.valueOf(seq + 1),
                "p", String.valueOf(paceSeconds));
        return new TrackStream("run-vendor", type, seq, null, seq + 1, paceSeconds, fields);
    }

    /** 单条记录 + 固定轨迹 + 给定原始流的内存仓储。 */
    private SportHealthTools toolsWithStreams(RunActivity run, List<TrackPoint> route, List<TrackStream> streams) {
        RunDataset dataset = new RunDataset(Instant.parse("2026-11-03T12:00:00Z"), "memory",
                List.of(), List.of(run), List.of());
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
                return route;
            }

            @Override
            public List<TrackStream> streams(String runId, String streamType) {
                return streams.stream()
                        .filter(stream -> streamType == null || streamType.equals(stream.streamType()))
                        .toList();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService service = new RunAnalyticsService(fake, new DateRangeResolver(clock));
        return new SportHealthTools(service, fake, properties, null);
    }

    /** 用固定的两点替换真实仓储的 {@code route()}，其它行为（dataset、lookup）保持真实。 */
    private SportHealthTools toolsWithRoute(List<TrackPoint> route) {
        HealthDataRepository fake = new HealthDataRepository() {
            private final HealthDataRepository delegate =
                    new TcxRunRepository(properties, new TcxParser(properties));

            @Override
            public RunDataset dataset() {
                return delegate.dataset();
            }

            @Override
            public void reload() {
                delegate.reload();
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return route;
            }
        };
        return new SportHealthTools(analytics, fake, properties, null);
    }

    /**
     * {@code clean} 必须和明细严格一致：重复 Activity Id、缺海拔都要让它变成 false。
     *
     * <p>旧实现的 clean 只合取了 6 项，漏掉这两项，会出现 clean=true 而
     * {@code duplicateActivityIds} / {@code missingAltitudeRunIds} 非空的自相矛盾结果；
     * 只信 clean 的调用方就漏检了。
     */
    @Test
    void cleanIsFalseWhenActivityIdsDuplicateOrAltitudeMissing() {
        Map<String, Object> duplicated = toolsWithActivities(List.of(
                sampleRun("run-a", "act-1", 48.0, 300d),
                sampleRun("run-b", "act-1", 51.0, 305d))).describeDataSource();
        Map<?, ?> duplicateQuality = (Map<?, ?>) duplicated.get("quality");
        assertEquals(List.of("act-1"), duplicateQuality.get("duplicateActivityIds"));
        assertFalse((Boolean) duplicateQuality.get("clean"), "活动 id 重复不能算 clean");

        Map<String, Object> noAltitude = toolsWithActivities(List.of(
                sampleRun("run-c", "act-2", null, 310d))).describeDataSource();
        Map<?, ?> altitudeQuality = (Map<?, ?>) noAltitude.get("quality");
        assertEquals(List.of("run-c"), altitudeQuality.get("missingAltitudeRunIds"));
        assertFalse((Boolean) altitudeQuality.get("clean"), "缺海拔不能算 clean");

        Map<String, Object> healthy = toolsWithActivities(List.of(
                sampleRun("run-d", "act-3", 50.0, 300d))).describeDataSource();
        assertEquals(Boolean.TRUE, ((Map<?, ?>) healthy.get("quality")).get("clean"), "样样通过时仍为 clean");
    }

    /** 一条最小可用记录：trackPointCount 为 0，避免触发需要读轨迹的重复时间戳检查。 */
    private static RunActivity sampleRun(String id, String activityId, Double minAltitude, Double paceSeconds) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 6, 56, 16);
        return new RunActivity(id, activityId, "户外跑步", start.toLocalDate(), start, start.plusMinutes(45),
                Instant.parse("2026-10-24T22:56:16Z"), 2700d, 8000d, 30d, 28d, "2", "OUTDOOR", 0,
                10.6, paceSeconds, 39.9057, 116.3972, 39.906, 116.398,
                minAltitude, minAltitude == null ? null : minAltitude + 20, id + ".tcx", "HUAWEI");
    }

    /** 用给定记录构造一个内存仓储，quality 检查全部基于 dataset，不需要真实文件。 */
    private SportHealthTools toolsWithActivities(List<RunActivity> activities) {
        RunDataset dataset = new RunDataset(Instant.parse("2026-11-03T12:00:00Z"), "memory",
                List.of(), activities, List.of());
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
                return List.of();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService service = new RunAnalyticsService(fake, new DateRangeResolver(clock));
        return new SportHealthTools(service, fake, properties, null);
    }

    private static void assertTimeText(Object value) {
        assertTrue(value instanceof String, "时间必须是字符串，实际：" + value);
        assertTrue(((String) value).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间格式必须是 yyyy-MM-dd HH:mm:ss，实际：" + value);
    }

    @Test
    void roundsRouteAltitudeToTwoDecimals() {
        Map<String, Object> pointsResult = tools.getRunRoute("20261025户外跑步", 0, 5, null, null, null);
        int checked = 0;
        for (Object item : (List<?>) pointsResult.get("points")) {
            checked += assertAltitudePrecision(((Map<?, ?>) item).get("altitudeMeters"));
        }
        assertTrue(checked > 0, "样本轨迹带海拔");

        Map<String, Object> geo = tools.getRunRoute("20261025户外跑步", null, null, "geojson", 5, null);
        Map<?, ?> geometry = (Map<?, ?>) geo.get("geometry");
        int geoChecked = 0;
        for (Object item : (List<?>) geometry.get("coordinates")) {
            List<?> coordinate = (List<?>) item;
            if (coordinate.size() >= 3) {
                geoChecked += assertAltitudePrecision(coordinate.get(2));
            }
        }
        assertTrue(geoChecked > 0, "geojson 坐标带海拔");
    }

    private static int assertAltitudePrecision(Object value) {
        if (!(value instanceof Number altitude)) {
            return 0;
        }
        assertEquals(Math.round(altitude.doubleValue() * 100d) / 100d, altitude.doubleValue(), 1e-9,
                "海拔应保留 2 位小数");
        return 1;
    }
}
