package org.example.sporthealth.domain;

import java.time.Instant;

/**
 * 一次跑步的天气：起跑与结束两个时刻。
 *
 * <p>{@code startQuery*} / {@code endQuery*} 记录真正发给 open-meteo 的坐标，而不是跑步记录里的原始坐标。
 * 原因是 open-meteo 会把任意坐标归到最近的模型网格（约 0.25°，几十公里），
 * 排查「为什么这条记录的天气和隔壁那条一样」时，只看原始坐标会误导。
 *
 * <p>状态：
 * <ul>
 *   <li>{@code OK}：至少一个时刻取到了有效数据；</li>
 *   <li>{@code UNAVAILABLE}：接口正常，但覆盖不到这个时段（例如太早的历史、archive 延迟窗口内），
 *       属于终态，不再重试；</li>
 *   <li>{@code FAILED}：网络、限流或响应格式问题，过 {@code retryFailedAfterDays} 天后重试。</li>
 * </ul>
 *
 * @param runId         跑步记录 id
 * @param start         起跑时刻天气
 * @param end           结束时刻天气
 * @param startLatitude 起跑天气查询所用纬度
 * @param startLongitude 起跑天气查询所用经度
 * @param endLatitude   结束天气查询所用纬度
 * @param endLongitude  结束天气查询所用经度
 * @param status        见类注释
 * @param errorMessage  {@code FAILED} / 部分缺失时的说明，其它情况为 null
 * @param fetchedAt     抓取时间
 * @param provider      数据来源标识，当前固定 open-meteo
 */
public record RunWeather(
        String runId,
        WeatherSnapshot start,
        WeatherSnapshot end,
        Double startLatitude,
        Double startLongitude,
        Double endLatitude,
        Double endLongitude,
        Status status,
        String errorMessage,
        Instant fetchedAt,
        String provider) {

    public static final String PROVIDER = "open-meteo";

    public enum Status {
        OK,
        /** 接口可用但没有覆盖该时段，属于终态。 */
        UNAVAILABLE,
        /** 请求失败，允许冷却后重试。 */
        FAILED
    }

    /** 两个时刻都拿到有效数据时的结果。 */
    public static RunWeather ok(String runId, Query startQuery, Query endQuery, WeatherSnapshot start,
                                WeatherSnapshot end, Instant fetchedAt) {
        return new RunWeather(runId, start, end,
                startQuery.latitude(), startQuery.longitude(),
                endQuery.latitude(), endQuery.longitude(),
                Status.OK, partialMessage(start, end), fetchedAt, PROVIDER);
    }

    /** 一个时刻都没拿到：区分「接口没覆盖」与「请求失败」，决定要不要重试。 */
    public static RunWeather empty(String runId, Query startQuery, Query endQuery, String reason,
                                   boolean retryable, Instant fetchedAt) {
        return new RunWeather(runId, null, null,
                startQuery.latitude(), startQuery.longitude(),
                endQuery.latitude(), endQuery.longitude(),
                retryable ? Status.FAILED : Status.UNAVAILABLE, reason, fetchedAt, PROVIDER);
    }

    /** 是否还需要向接口再问一次。 */
    public boolean retryable() {
        return status == Status.FAILED;
    }

    /**
     * 记录本身没有可用坐标（室内跑步、GPS 漂移为 0,0）时，天气无从查起，
     * 直接标记为不可用而不是发一个无效请求。
     */
    public static RunWeather noLocation(String runId, Instant fetchedAt) {
        return new RunWeather(runId, null, null, null, null, null, null,
                Status.UNAVAILABLE, "记录缺少有效坐标", fetchedAt, PROVIDER);
    }

    private static String partialMessage(WeatherSnapshot start, WeatherSnapshot end) {
        boolean startMissing = start == null || start.isEmpty();
        boolean endMissing = end == null || end.isEmpty();
        if (startMissing && endMissing) {
            return null;
        }
        if (startMissing) {
            return "起跑时刻没有可用的天气数据（通常早于历史存档覆盖范围）";
        }
        if (endMissing) {
            return "结束时刻没有可用的天气数据";
        }
        return null;
    }

    /** 一次查询的入参：坐标 + 时刻，坐标已经过有效性校验。 */
    public record Query(double latitude, double longitude, Instant at) {

        /** 转成客户端需要的坐标对象（已归一化到 4 位小数）。 */
        public org.example.sporthealth.weather.OpenMeteoClient.Coordinate coordinate() {
            return new org.example.sporthealth.weather.OpenMeteoClient.Coordinate(latitude, longitude);
        }

        /** 0,0 是 GPS 未定位时的常见默认值，不能拿去查天气。 */
        public boolean valid() {
            return at != null
                    && latitude >= -90d && latitude <= 90d
                    && longitude >= -180d && longitude <= 180d
                    && (Math.abs(latitude) > 1e-6 || Math.abs(longitude) > 1e-6);
        }
    }
}
