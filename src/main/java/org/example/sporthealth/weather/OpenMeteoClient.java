package org.example.sporthealth.weather;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.WeatherProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

/**
 * open-meteo 的只读客户端，负责「给定若干坐标与一个日期，拿回逐小时序列」。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li><b>一次请求取起终点两个坐标</b>：经纬度用逗号拼接，接口返回 JSON <b>数组</b>，
 *       顺序与请求一致。起跑与结束通常同一天同一片区域，这样每次回填只花一次配额；</li>
 *   <li><b>明确 {@code timezone=UTC}</b>：不传时接口按地理时区返回本地时间，与库里存 UTC 的
 *       约定冲突，跨时区时会出现整小时错位；</li>
 *   <li><b>按日期精确取</b>：{@code start_date=end_date=当天}，只返回 24 个点，
 *       而不是默认的 7×24；</li>
 *   <li><b>不在这里重试</b>：重试由 {@code WeatherBackfill} 在「下一次同步」层面做，
 *       客户端再套一层只会让单次同步卡得更久。</li>
 * </ul>
 *
 * <p>服务是阻塞式的（MCP {@code type: SYNC}），这里用 {@code block} 等结果；
 * 超时设在单次请求上，不改动全局 WebClient。
 */
@Component
@Slf4j
public class OpenMeteoClient {

    /** 多坐标时接口返回数组，统一按 List 解析。 */
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final WeatherProperties properties;

    public OpenMeteoClient(WebClient webClient, WeatherProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /** 数据源，决定用预报端点还是历史存档端点。 */
    public enum Endpoint {
        /** 覆盖今天往前 {@code recentWindowDays} 天，含预报实况。 */
        FORECAST,
        /** 更早的历史，由 ERA5 等再分析资料构成，有数天延迟。 */
        ARCHIVE
    }

    /**
     * 取某一天的逐小时序列。
     *
     * @param endpoint 数据源
     * @param area     坐标列表，顺序即返回顺序；重复坐标会先去重以减小响应体积
     * @param date     UTC 日期
     * @return 与 {@code area} 等长的序列列表，取不到的位置是空序列
     * @throws OpenMeteoException 请求失败或限流
     */
    public List<HourlySeries> fetchHourly(Endpoint endpoint, List<Coordinate> area, LocalDate date) {
        if (area.isEmpty()) {
            return List.of();
        }
        // 起终点常常落在同一格，去重能省掉一半响应体积；用 LinkedHashMap 保留首次出现顺序
        Map<Coordinate, Integer> distinct = new LinkedHashMap<>();
        for (Coordinate coordinate : area) {
            distinct.computeIfAbsent(coordinate, key -> distinct.size());
        }

        URI uri = buildUri(endpoint, List.copyOf(distinct.keySet()), date);
        List<Map<String, Object>> items = request(uri, distinct.size());
        List<HourlySeries> distinctSeries = new ArrayList<>(distinct.size());
        for (Map<String, Object> item : items) {
            distinctSeries.add(toSeries(item));
        }
        while (distinctSeries.size() < distinct.size()) {
            distinctSeries.add(HourlySeries.from(null));
        }

        List<HourlySeries> result = new ArrayList<>(area.size());
        for (Coordinate coordinate : area) {
            Integer index = distinct.get(coordinate);
            result.add(index == null || index >= distinctSeries.size()
                    ? HourlySeries.from(null) : distinctSeries.get(index));
        }
        return result;
    }

    private List<Map<String, Object>> request(URI uri, int expectedCount) {
        try {
            // 单坐标返回对象、多坐标返回数组，分别解析，省掉一次「先解析成 Object 再强转」
            if (expectedCount == 1) {
                Map<String, Object> single = webClient.get()
                        .uri(uri)
                        // 只声明期望的媒体类型；响应仍按它自己的 Content-Type 选择解码器，
                        // 网关改写类型时会走下面的 RuntimeException 分支，按可重试失败处理
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(OBJECT_TYPE)
                        .switchIfEmpty(Mono.fromSupplier(Map::of))
                        .block(properties.timeout());
                return single == null || single.isEmpty() ? List.of() : List.of(single);
            }
            List<Map<String, Object>> list = webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(LIST_TYPE)
                    .block(properties.timeout());
            return list == null ? List.of() : list;
        } catch (WebClientResponseException.TooManyRequests e) {
            throw new OpenMeteoException("open-meteo 限流（429），稍后重试", e, true);
        } catch (WebClientResponseException e) {
            // 只有 400 能确定是「这个时段查不到」（日期超出该端点范围），重试没用；
            // 其它 4xx（401/403/404/422…）多半是地址、鉴权或参数写错，冷却后重试才能让问题
            // 重新暴露出来。把它们一并判成终态，等于把配置错误永久藏进 UNAVAILABLE 里。
            boolean retryable = e.getStatusCode().value() != HttpStatus.BAD_REQUEST.value();
            throw new OpenMeteoException("open-meteo 返回 " + e.getStatusCode().value()
                    + "：" + shorten(e.getResponseBodyAsString()), e, retryable);
        } catch (OpenMeteoException e) {
            throw e;
        } catch (RuntimeException e) {
            // 超时、连接失败、DNS 异常都归到这里
            throw new OpenMeteoException("访问 open-meteo 失败：" + describe(e), e, true);
        }
    }

    private URI buildUri(Endpoint endpoint, List<Coordinate> coordinates, LocalDate date) {
        String baseUrl = endpoint == Endpoint.ARCHIVE
                ? properties.archiveBaseUrl() : properties.forecastBaseUrl();
        String day = date.toString();
        // 变量名只含 [a-z0-9_]，逗号是 query 分隔符，无需编码
        String hourly = String.join(",", WeatherInterpolator.VARIABLES);
        String latitudes = joinDouble(coordinates, Coordinate::latitude);
        String longitudes = joinDouble(coordinates, Coordinate::longitude);
        return URI.create(baseUrl
                + "?latitude=" + latitudes
                + "&longitude=" + longitudes
                + "&start_date=" + day
                + "&end_date=" + day
                + "&hourly=" + hourly
                + "&timezone=UTC");
    }

    private static String joinDouble(List<Coordinate> coordinates,
                                     java.util.function.ToDoubleFunction<Coordinate> extractor) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            // 4 位小数约 11 米，远超模型 0.25° 分辨率，避免把浮点噪声写进 URL
            builder.append(String.format(Locale.ROOT, "%.4f", extractor.applyAsDouble(coordinates.get(i))));
        }
        return builder.toString();
    }

    /** 把单个元素的 {@code hourly} 节点转成序列；结构不对时返回空序列而不是抛异常。 */
    private static HourlySeries toSeries(Map<String, Object> item) {
        Object hourly = item == null ? null : item.get("hourly");
        if (!(hourly instanceof Map<?, ?> map)) {
            return HourlySeries.from(null);
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                typed.put(key.toString(), value);
            }
        });
        return HourlySeries.from(typed);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    /** 查询用的坐标，4 位小数归一化，便于去重。 */
    public record Coordinate(double latitude, double longitude) {

        public Coordinate {
            latitude = round(latitude);
            longitude = round(longitude);
        }

        private static double round(double value) {
            return Math.round(value * 10_000d) / 10_000d;
        }
    }

    /** 客户端异常；{@code retryable} 决定记录落成 {@code FAILED} 还是 {@code UNAVAILABLE}。 */
    public static final class OpenMeteoException extends RuntimeException {

        private final boolean retryable;

        public OpenMeteoException(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** 插值只用秒级精度，纳秒会让相邻点的比较出现无意义的差异。 */
    public static java.time.Instant truncate(java.time.Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
    }

    /** open-meteo 的日期参数是 UTC 日期，与库里的 {@code start_time_utc} 对齐。 */
    public static LocalDate utcDate(java.time.Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }
}
