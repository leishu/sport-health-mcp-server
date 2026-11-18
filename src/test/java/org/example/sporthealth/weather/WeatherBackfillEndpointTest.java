package org.example.sporthealth.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.repository.RunWeatherStore;
import org.example.sporthealth.weather.OpenMeteoClient.Endpoint;
import org.junit.jupiter.api.Test;

/**
 * 端点分流的边界：窗口内走 forecast，窗口外走 archive，
 * {@code archive-lag-days} 把 forecast 窗口最老的几天交给 archive。
 *
 * <p>这条规则很容易写错且错得很隐蔽：archive 对最近几天返回 200 但全是 null，
 * 落错端点会把「暂时没数据」误判成 {@code UNAVAILABLE} 终态，从此不再重试。
 * 所以单独钉住边界，不在回填的端到端测试里顺带验证。
 */
class WeatherBackfillEndpointTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    /** 窗口 92 天、lag 5 天：forecast 实际只覆盖最近 87 天，第 88 天交给 archive。 */
    @Test
    void lagShrinksTheForecastWindow() {
        WeatherBackfill backfill = backfill(92, 5);
        LocalDate boundary = date(87);

        assertEquals(Endpoint.FORECAST, backfill.endpointFor(boundary, NOW));
        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(boundary.minusDays(1), NOW),
                "再往前一天交给 archive");
    }

    /** 距今很近的日期必须走 forecast，这是 archive 会返回全 null 的那一段。 */
    @Test
    void recentDatesGoToForecast() {
        WeatherBackfill backfill = backfill(92, 5);

        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(0), NOW), "今天");
        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(3), NOW), "archive 还没追上");
        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(30), NOW), "一个月前");
    }

    /** 远超窗口的早期记录走 archive。 */
    @Test
    void oldDatesGoToArchive() {
        WeatherBackfill backfill = backfill(92, 5);

        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(date(200), NOW));
        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(date(1000), NOW));
    }

    /** 延迟为 0 时窗口不留余量，分界线正好落在「今天 - 92 天」。 */
    @Test
    void zeroLagKeepsTheRawWindow() {
        WeatherBackfill backfill = backfill(92, 0);

        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(92), NOW));
        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(date(93), NOW));
    }

    /**
     * 延迟配得比窗口还大时，构造器把它压回 0，分界线回到原始窗口，
     * 今天和窗口内的日期仍走 forecast，不会被整体推给 archive。
     */
    @Test
    void oversizedLagIsClampedToZero() {
        WeatherBackfill backfill = backfill(3, 30);

        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(0), NOW), "今天不能被推给 archive");
        assertEquals(Endpoint.FORECAST, backfill.endpointFor(date(3), NOW));
        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(date(4), NOW));
    }

    /** 判断基准来自传入的 now，而不是系统时钟：跨零点运行时整轮要保持一致。 */
    @Test
    void usesTheProvidedNowAsTheReferenceDate() {
        WeatherBackfill backfill = backfill(92, 5);
        LocalDate target = date(87);

        // 同一目标日期，now 推后一天后它就越过边界掉进 archive
        assertEquals(Endpoint.FORECAST, backfill.endpointFor(target, NOW));
        assertEquals(Endpoint.ARCHIVE, backfill.endpointFor(target, NOW.plus(Duration.ofDays(1))));
    }

    // ------------------------------------------------------------------

    private static LocalDate date(int daysAgo) {
        return NOW.atZone(ZoneOffset.UTC).toLocalDate().minusDays(daysAgo);
    }

    private static WeatherBackfill backfill(int windowDays, int lagDays) {
        WeatherProperties properties = new WeatherProperties(true, "http://localhost/v1/forecast",
                "http://localhost/v1/archive", windowDays, lagDays, Duration.ofSeconds(1), 7, 50);
        // endpointFor 只用到 properties，两个依赖传 null 即可，避免为纯逻辑测试搭数据库
        return new WeatherBackfill(null, null, properties);
    }
}
