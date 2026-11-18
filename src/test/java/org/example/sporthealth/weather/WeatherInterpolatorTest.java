package org.example.sporthealth.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.WeatherSnapshot;
import org.junit.jupiter.api.Test;

/**
 * 插值是最容易悄悄出错的一环：错一小时不会报错，只会让「跑步时几度」这个结论系统性偏移。
 * 所以这里对每个变量单独给出可手算的期望值。
 */
class WeatherInterpolatorTest {

    private static final Instant H22 = Instant.parse("2026-11-02T22:00:00Z");
    private static final Instant H23 = Instant.parse("2026-11-02T23:00:00Z");
    private static final Instant H00 = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    void interpolatesContinuousVariablesLinearly() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(
                        WeatherInterpolator.TEMPERATURE, List.of(10.0, 20.0),
                        WeatherInterpolator.APPARENT_TEMPERATURE, List.of(8.0, 18.0),
                        WeatherInterpolator.HUMIDITY, List.of(40.0, 80.0),
                        WeatherInterpolator.PRECIPITATION, List.of(0.0, 1.0),
                        WeatherInterpolator.WIND_SPEED, List.of(5.0, 15.0),
                        WeatherInterpolator.WIND_DIRECTION, List.of(90.0, 90.0),
                        WeatherInterpolator.WEATHER_CODE, List.of(0.0, 61.0)));

        // 22:45 是 22:00 → 23:00 的 75%
        WeatherSnapshot snapshot = WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:45:00Z"));

        assertNotNull(snapshot);
        assertEquals(H22, snapshot.observedAtUtc(), "对齐点记为 floor 那一小时，便于回溯");
        assertEquals(17.5, snapshot.temperatureC(), 1e-9);
        assertEquals(15.5, snapshot.apparentTemperatureC(), 1e-9);
        assertEquals(70.0, snapshot.humidityPercent(), 1e-9);
        assertEquals(0.75, snapshot.precipitationMm(), 1e-9);
        assertEquals(12.5, snapshot.windSpeedKmh(), 1e-9);
    }

    /**
     * 回归用例：风向必须按圆周插值。
     *
     * <p>350° → 10° 的中点应该是 0°（正北），按数字线性平均会得到 180°（正南），
     * 把「北风」算成「南风」，方向完全反了。
     */
    @Test
    void interpolatesWindDirectionAlongTheShortestArc() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.WIND_DIRECTION, List.of(350.0, 10.0)));

        WeatherSnapshot half = WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:30:00Z"));
        assertEquals(0.0, half.windDirectionDegrees(), 1e-9, "跨 0° 应走最短弧");

        WeatherSnapshot quarter = WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:15:00Z"));
        assertEquals(355.0, quarter.windDirectionDegrees(), 1e-9);
    }

    @Test
    void windDirectionWrapsBackwardsAcrossZero() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.WIND_DIRECTION, List.of(10.0, 350.0)));

        WeatherSnapshot half = WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:30:00Z"));
        // 10° → 350° 的最短弧是 -20°，中点是 0°，而不是 180°
        assertEquals(0.0, half.windDirectionDegrees(), 1e-9);
    }

    @Test
    void weatherCodeSnapsToNearestHourInsteadOfInterpolating() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.WEATHER_CODE, List.of(0.0, 61.0)));

        // 22:20 更接近 22:00 的晴
        assertEquals(0, WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:20:00Z")).weatherCode());
        // 22:40 更接近 23:00 的小雨
        assertEquals(61, WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:40:00Z")).weatherCode());
    }

    /** 早于首点不能取值：那属于另一个数据窗口，取出来是错的。 */
    @Test
    void returnsNullBeforeTheFirstPoint() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, List.of(10.0, 20.0)));

        assertNull(WeatherInterpolator.at(series, Instant.parse("2026-11-02T21:59:00Z")));
    }

    /**
     * 回归用例：按天取数时最后一个是 23:00，覆盖的是 23:00–24:00 这一小时。
     * 23:37 结束的跑步必须拿到末点数据，不能白丢一个时刻。
     */
    @Test
    void borrowsTheLastPointWithinOneHourAfterIt() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, List.of(10.0, 20.0)));

        WeatherSnapshot snapshot = WeatherInterpolator.at(series, Instant.parse("2026-11-02T23:37:00Z"));
        assertNotNull(snapshot);
        assertEquals(20.0, snapshot.temperatureC(), 1e-9);
        assertEquals(H23, snapshot.observedAtUtc(), "记录末点，便于回溯用的是哪一格");
    }

    @Test
    void doesNotBorrowBeyondOneHourAfterTheLastPoint() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, List.of(10.0, 20.0)));

        assertNull(WeatherInterpolator.at(series, Instant.parse("2026-11-03T00:30:00Z")),
                "超出末点一小时就不该再借用，否则等于外推");
    }

    @Test
    void lastPointIsReturnedAsIs() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, List.of(10.0, 20.0)));

        WeatherSnapshot snapshot = WeatherInterpolator.at(series, H23);
        assertNotNull(snapshot);
        assertEquals(20.0, snapshot.temperatureC(), 1e-9);
        assertEquals(H23, snapshot.observedAtUtc());
    }

    /** 一侧缺值就用另一侧，不能因为单点 null 把整条记录判成无数据。 */
    @Test
    void fallsBackToTheAvailableSideWhenOneHourIsMissing() {
        HourlySeries series = series(
                List.of(H22.toString(), H23.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, java.util.Arrays.asList(null, 20.0)));

        WeatherSnapshot snapshot = WeatherInterpolator.at(series, Instant.parse("2026-11-02T22:30:00Z"));
        assertEquals(20.0, snapshot.temperatureC(), 1e-9);
    }

    /**
     * 回归用例：时间轴里出现解析不了的字符串时，只丢掉那一格，不能把整条记录变成失败。
     *
     * <p>早期实现用 {@code List.copyOf} 保存时间轴，null 元素会抛 NPE，异常冒到回填层后被包成
     * 「可重试失败」，一条格式错误就让全部记录白等一个冷却周期；改成保留 null 也不行，
     * 二分查找碰到 null 会漏掉正确下标。
     */
    @Test
    void unparsableTimeOnlyDropsThatHour() {
        HourlySeries series = series(
                List.of(H22.toString(), "2026-11-02T23:00-ish", H00.toString()),
                Map.of(WeatherInterpolator.TEMPERATURE, List.of(10.0, 15.0, 20.0)));

        assertEquals(2, series.times().size(), "无效的那一格要从时间轴里剔掉");
        assertEquals(List.of(H22, H00), series.times(), "剩下的时间保持原顺序，不打乱对齐");
        assertEquals(20.0, series.value(WeatherInterpolator.TEMPERATURE, 1), 1e-9,
                "变量序列要跟着一起重排，否则温度会错位一小时");
        assertEquals(10.0, WeatherInterpolator.at(series, H22).temperatureC(), 1e-9);
    }

    @Test
    void emptySeriesReturnsNull() {
        assertNull(WeatherInterpolator.at(HourlySeries.from(null), H22));
        assertNull(WeatherInterpolator.at(null, H22));
    }

    @Test
    void snapshotReportsEmptyWhenNothingWasAvailable() {
        WeatherSnapshot snapshot = WeatherInterpolator.at(series(List.of(H22.toString()), Map.of()), H22);
        assertNotNull(snapshot, "只有时间轴、没有变量时仍返回快照");
        assertTrue(snapshot.isEmpty(), "全空快照由调用方判为 UNAVAILABLE");
    }

    // ------------------------------------------------------------------

    private static HourlySeries series(List<String> times, Map<String, List<Double>> values) {
        Map<String, Object> hourly = new LinkedHashMap<>();
        hourly.put("time", times);
        hourly.putAll(values);
        return HourlySeries.from(hourly);
    }
}
