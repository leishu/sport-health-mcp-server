package org.example.sporthealth.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.WeatherSnapshot;
import org.example.sporthealth.weather.OpenMeteoClient.Coordinate;
import org.example.sporthealth.weather.OpenMeteoClient.Endpoint;
import org.example.sporthealth.weather.OpenMeteoClient.OpenMeteoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 客户端测试，用 MockWebServer 而不是真接口：需要精确控制返回形状（数组 / 单对象）、
 * 状态码与缺值情况，真接口这些都不可控。
 */
class OpenMeteoClientTest {

    private MockWebServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/v1/forecast").toString();
        WeatherProperties properties = new WeatherProperties(
                true, baseUrl, baseUrl, 92, 5, java.time.Duration.ofSeconds(5), 7, 50);
        client = new OpenMeteoClient(WebClient.create(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsBothCoordinatesInOneRequestWithUtcTimezone() throws Exception {
        server.enqueue(json(arrayBody(2)));
        List<HourlySeries> series = client.fetchHourly(Endpoint.FORECAST,
                List.of(new Coordinate(39.901862, 116.18425), new Coordinate(39.905, 116.19)),
                LocalDate.parse("2026-11-02"));

        RecordedRequest request = server.takeRequest();
        String path = request.getPath();
        assertNotNull(path);
        assertTrue(path.contains("latitude=39.9019,39.9050"), path);
        assertTrue(path.contains("longitude=116.1843,116.1900"), path);
        assertTrue(path.contains("start_date=2026-11-02"), path);
        assertTrue(path.contains("end_date=2026-11-02"), "只取当天，不要默认 7 天: " + path);
        assertTrue(path.contains("timezone=UTC"), "统一 UTC，避免按地理时区返回本地时间: " + path);
        assertTrue(path.contains("apparent_temperature"), "体感温度必须请求: " + path);

        assertEquals(2, series.size());
        assertEquals(2, series.get(0).times().size(), "桩数据每天两个点，关键是顺序对齐");
    }

    @Test
    void parsesSingleObjectResponseWithOneCoordinate() {
        server.enqueue(json(singleBody()));

        List<HourlySeries> series = client.fetchHourly(Endpoint.FORECAST,
                List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2026-11-02"));

        assertEquals(1, series.size());
        WeatherSnapshot snapshot = WeatherInterpolator.at(series.get(0), Instant.parse("2026-11-02T22:30:00Z"));
        assertNotNull(snapshot);
        assertEquals(13.0, snapshot.temperatureC(), 1e-9);
    }

    /** 起终点落在同一格时只查一次，但返回列表长度必须与入参对齐。 */
    @Test
    void deduplicatesIdenticalCoordinatesButKeepsResultAligned() {
        server.enqueue(json(singleBody()));
        Coordinate same = new Coordinate(39.901862, 116.18425);

        List<HourlySeries> series = client.fetchHourly(Endpoint.FORECAST, List.of(same, same),
                LocalDate.parse("2026-11-02"));

        assertEquals(2, series.size());
        assertEquals(2, series.get(0).times().size(), "桩数据每天两个点，关键是两份结果都拿到了");
        assertEquals(2, series.get(1).times().size());
        assertEquals(series.get(0).times(), series.get(1).times(), "同一坐标共用同一份数据");
    }

    /** 429 属于限流，必须标记为可重试，否则记录会被永久判成「无数据」。 */
    @Test
    void marksRateLimitAsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":true}"));

        OpenMeteoException e = assertThrows(OpenMeteoException.class, () -> client.fetchHourly(
                Endpoint.FORECAST, List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2026-11-02")));

        assertTrue(e.retryable());
        assertTrue(e.getMessage().contains("429"), e.getMessage());
    }

    /** 5xx 是服务端临时故障，冷却后重试通常能成功。 */
    @Test
    void marksServerErrorAsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(502));

        OpenMeteoException e = assertThrows(OpenMeteoException.class, () -> client.fetchHourly(
                Endpoint.ARCHIVE, List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2024-01-01")));

        assertTrue(e.retryable());
    }

    /** 400 通常是日期超出该端点范围，重试没有意义，直接落 UNAVAILABLE。 */
    @Test
    void marksBadRequestAsNotRetryable() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":true}"));

        OpenMeteoException e = assertThrows(OpenMeteoException.class, () -> client.fetchHourly(
                Endpoint.ARCHIVE, List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2001-01-01")));

        assertFalse(e.retryable());
    }

    /**
     * 400 以外的 4xx（404/401/422…）不是「这个时段没数据」，多半是端点地址或鉴权配错。
     * 判成终态会把配置问题永久藏进 UNAVAILABLE 里，所以必须可重试。
     */
    @Test
    void marksOtherClientErrorsAsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(404));

        OpenMeteoException e = assertThrows(OpenMeteoException.class, () -> client.fetchHourly(
                Endpoint.FORECAST, List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2026-11-02")));

        assertTrue(e.retryable(), "404 是地址配错，不是没有数据");
    }

    /** 缺值的小时在真实响应里就是 null，必须保持 null 而不是变成 0。 */
    @Test
    void keepsNullHoursAsNull() {
        server.enqueue(json("""
                {"hourly":{"time":["2026-11-02T00:00","2026-11-02T01:00"],
                           "temperature_2m":[null,12.5]}}
                """));

        HourlySeries series = client.fetchHourly(Endpoint.ARCHIVE,
                List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2026-11-02")).get(0);

        assertNull(series.value(WeatherInterpolator.TEMPERATURE, 0));
        assertEquals(12.5, series.value(WeatherInterpolator.TEMPERATURE, 1), 1e-9);
    }

    /** 响应体为空不能当成失败：表示该时段没有数据。 */
    @Test
    void emptyBodyYieldsEmptySeries() {
        server.enqueue(json(""));

        List<HourlySeries> series = client.fetchHourly(Endpoint.FORECAST,
                List.of(new Coordinate(39.9, 116.18)), LocalDate.parse("2026-11-02"));

        assertEquals(1, series.size());
        assertTrue(series.get(0).isEmpty());
    }

    // ------------------------------------------------------------------

    /** 真实接口返回 application/json，测试桩也要带上，否则解码器会按字节流处理。 */
    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private static String singleBody() {
        return """
                {"latitude":39.89,"longitude":116.23,
                 "hourly":{"time":["2026-11-02T22:00","2026-11-02T23:00"],
                           "temperature_2m":[12.0,14.0],
                           "apparent_temperature":[10.0,12.0],
                           "relative_humidity_2m":[60,70],
                           "precipitation":[0.0,0.0],
                           "wind_speed_10m":[8.0,10.0],
                           "wind_direction_10m":[180,190],
                           "weather_code":[1,2]}}
                """;
    }

    /** 多坐标返回数组，元素顺序与请求顺序一致。 */
    private static String arrayBody(int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(singleBody());
        }
        return builder.append(']').toString();
    }
}
