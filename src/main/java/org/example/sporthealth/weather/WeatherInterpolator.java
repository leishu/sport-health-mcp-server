package org.example.sporthealth.weather;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.example.sporthealth.domain.WeatherSnapshot;

/**
 * 把「任意时刻」映射到 open-meteo 的逐小时数据上。
 *
 * <p>为什么不能直接取整点：跑步很少整点开始（例如 22:45:36），若取 floor 那一格，
 * 误差最坏接近 1 小时——在日落前后或冷锋过境时，这一小时能差好几度。
 * 反过来取 ceil 也不对，总是偏向未来。因此对<b>连续型</b>变量做相邻两小时线性插值，
 * {@code observedAtUtc} 记为 floor 那一格，作为回溯依据。
 *
 * <p>不同变量的插值规则：
 * <ul>
 *   <li>气温、体感、湿度、风速、降水：线性插值；任一端缺值就退化为取有值的一侧，
 *       两侧都缺则返回 null（早期数据用 null 填充，绝不能当 0 用）；</li>
 *   <li>风向：按<b>圆周</b>插值。350° 与 10° 的中间值是 0°，不是线性平均得到的 180°，
 *       后者会把「北风」算成「南风」，方向完全反了；</li>
 *   <li>天气代码：枚举值不插值，取更近的整点。</li>
 * </ul>
 */
public final class WeatherInterpolator {

    public static final String TEMPERATURE = "temperature_2m";
    public static final String APPARENT_TEMPERATURE = "apparent_temperature";
    public static final String HUMIDITY = "relative_humidity_2m";
    public static final String PRECIPITATION = "precipitation";
    public static final String WIND_SPEED = "wind_speed_10m";
    public static final String WIND_DIRECTION = "wind_direction_10m";
    public static final String WEATHER_CODE = "weather_code";

    /** 一次请求取用的全部变量，顺序即 URL 参数顺序，便于日志比对。 */
    public static final List<String> VARIABLES = List.of(
            TEMPERATURE, APPARENT_TEMPERATURE, HUMIDITY, PRECIPITATION,
            WIND_SPEED, WIND_DIRECTION, WEATHER_CODE);

    private WeatherInterpolator() {
    }

    /**
     * 末点之后仍可借用的时间跨度。
     *
     * <p>open-meteo 按天返回时，最后一个是当天 23:00，覆盖的是 23:00–24:00 这一小时。
     * 23:37 结束的跑步不借用末点就会白丢一个时刻，而借用它的偏差不超过一小时——与逐小时
     * 数据本身的精度同量级。更早的首点侧不需要类似放宽：跑步时刻总是从当天某点开始。
     */
    private static final Duration TAIL_TOLERANCE = Duration.ofHours(1);

    /**
     * 在序列上取值；时刻落在序列覆盖范围之外时返回 null。
     *
     * <p>首点之前一律不取值（那属于另一个数据窗口，不是精度问题）。
     * 末点之后最多放宽 {@link #TAIL_TOLERANCE}，用的是末点自己的值而非外推。
     *
     * @param at 目标时刻，最好已截断到秒
     */
    public static WeatherSnapshot at(HourlySeries series, Instant at) {
        if (series == null || series.isEmpty() || at == null) {
            return null;
        }
        List<Instant> times = series.times();
        int last = times.size() - 1;
        Instant first = times.get(0);
        Instant end = times.get(last);

        if (at.isBefore(first)) {
            return null;
        }
        if (at.isAfter(end)) {
            // 超出末点太久说明确实没有覆盖，不外推：编出来的天气比 null 更糟
            return Duration.between(end, at).compareTo(TAIL_TOLERANCE) > 0
                    ? null
                    : snapshotAt(series, last, end);
        }

        int index = indexOfFloor(times, at);
        if (index < 0) {
            return null;
        }
        Instant floor = times.get(index);
        // 末点无法做区间插值，只能用它自己的值
        if (index >= last) {
            return snapshotAt(series, index, floor);
        }
        Instant ceil = times.get(index + 1);
        long spanSeconds = Duration.between(floor, ceil).getSeconds();
        double ratio = spanSeconds <= 0
                ? 0d
                : (double) Duration.between(floor, at).getSeconds() / spanSeconds;

        return new WeatherSnapshot(
                floor,
                lerp(series.value(TEMPERATURE, index), series.value(TEMPERATURE, index + 1), ratio),
                lerp(series.value(APPARENT_TEMPERATURE, index), series.value(APPARENT_TEMPERATURE, index + 1), ratio),
                lerp(series.value(HUMIDITY, index), series.value(HUMIDITY, index + 1), ratio),
                lerp(series.value(PRECIPITATION, index), series.value(PRECIPITATION, index + 1), ratio),
                lerp(series.value(WIND_SPEED, index), series.value(WIND_SPEED, index + 1), ratio),
                lerpDegrees(series.value(WIND_DIRECTION, index), series.value(WIND_DIRECTION, index + 1), ratio),
                nearestCode(series.value(WEATHER_CODE, index), series.value(WEATHER_CODE, index + 1), ratio));
    }

    /** 区间内线性插值；只有一侧有值时用那一侧，避免因为单点缺失丢掉整条记录。 */
    static Double lerp(Double from, Double to, double ratio) {
        if (from == null) {
            return to;
        }
        if (to == null) {
            return from;
        }
        return from + (to - from) * ratio;
    }

    /**
     * 角度插值：先取最短弧（差值折到 -180~180），插完再归一化到 0~360。
     *
     * <p>350° → 10° 的 {@code delta} 是 +20°，中点 0°；线性平均则会得到 180°，方向完全相反。
     */
    static Double lerpDegrees(Double from, Double to, double ratio) {
        if (from == null) {
            return to;
        }
        if (to == null) {
            return from;
        }
        double delta = ((to - from) % 360d + 540d) % 360d - 180d;
        double result = from + delta * ratio;
        return normalizeDegrees(result);
    }

    static Double normalizeDegrees(double degrees) {
        double normalized = degrees % 360d;
        return normalized < 0 ? normalized + 360d : normalized;
    }

    /** 枚举不插值：ratio < 0.5 取前一小时，否则取后一小时。 */
    static Integer nearestCode(Double from, Double to, double ratio) {
        Double picked = ratio < 0.5 ? (from != null ? from : to) : (to != null ? to : from);
        return picked == null ? null : Integer.valueOf((int) Math.round(picked));
    }

    private static WeatherSnapshot snapshotAt(HourlySeries series, int index, Instant observedAt) {
        Double code = series.value(WEATHER_CODE, index);
        return new WeatherSnapshot(
                observedAt,
                series.value(TEMPERATURE, index),
                series.value(APPARENT_TEMPERATURE, index),
                series.value(HUMIDITY, index),
                series.value(PRECIPITATION, index),
                series.value(WIND_SPEED, index),
                series.value(WIND_DIRECTION, index),
                code == null ? null : Integer.valueOf((int) Math.round(code)));
    }

    /** 二分找最后一个 <= at 的下标；早于首点时返回 -1。 */
    private static int indexOfFloor(List<Instant> times, Instant at) {
        int low = 0;
        int high = times.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Instant value = times.get(mid);
            if (value == null) {
                low = mid + 1;
                continue;
            }
            if (value.isAfter(at)) {
                high = mid - 1;
            } else {
                result = mid;
                low = mid + 1;
            }
        }
        return result;
    }
}
