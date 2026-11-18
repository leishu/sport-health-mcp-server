package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 工具层返回结构测试：quality 块与个人最佳的新类别。
 */
class SportHealthToolsTest {

    private SportHealthTools tools;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
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
        Map<String, Object> all = tools.getPersonalBests(null, null, "all");
        Map<?, ?> allBests = (Map<?, ?>) all.get("bests");
        assertNotNull(allBests.get("best1k"));
        assertNotNull(allBests.get("best5k"));

        Map<String, Object> only5k = tools.getPersonalBests(null, null, "5k");
        assertEquals("best5k", only5k.get("category"));
        Map<?, ?> only5kBests = (Map<?, ?>) only5k.get("bests");
        assertEquals(1, only5kBests.size());
        assertNotNull(only5kBests.get("best5k"));
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

        Map<String, Object> route = tools.getRunRoute("20261025户外跑步", 0, 1, null, null);
        assertTimeText(((Map<?, ?>) ((List<?>) route.get("points")).get(0)).get("timestamp"));

        assertTimeText(tools.describeDataSource().get("loadedAt"));
    }

    private static void assertTimeText(Object value) {
        assertTrue(value instanceof String, "时间必须是字符串，实际：" + value);
        assertTrue(((String) value).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间格式必须是 yyyy-MM-dd HH:mm:ss，实际：" + value);
    }

    @Test
    void roundsRouteAltitudeToTwoDecimals() {
        Map<String, Object> pointsResult = tools.getRunRoute("20261025户外跑步", 0, 5, null, null);
        int checked = 0;
        for (Object item : (List<?>) pointsResult.get("points")) {
            checked += assertAltitudePrecision(((Map<?, ?>) item).get("altitudeMeters"));
        }
        assertTrue(checked > 0, "样本轨迹带海拔");

        Map<String, Object> geo = tools.getRunRoute("20261025户外跑步", null, null, "geojson", 5);
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
