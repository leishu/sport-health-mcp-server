package org.example.sporthealth.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.HeartRateActivity;
import org.example.sporthealth.domain.HeartRateSample;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 自动心率明细的只读访问：{@code health_metric} 里 {@code metric_key='DYNAMIC_HEART_RATE'}。
 *
 * <p>读这张表有三个必须知道的事实（全库实测，349 483 点 / 368 天）：
 * <ul>
 *   <li><b>采样密度不是固定的</b>：2025-11 到 2026-06 那台设备每分钟一条（一天 1436 条），
 *       换设备后静息时拉长到 10 分钟一条、运动时才加密（一天 238~320 条）。
 *       所以「今天只有 250 个点」是正常采样，不是缺数据；</li>
 *   <li><b>运动时段会加密</b>：跑步那一个小时能有 50 条，是同一天其它小时的 5 倍；</li>
 *   <li><b>有 134 条记录带秒</b>（{@code 2025-12-21T11:00:45.358}），来自另一个数据源，
 *       截到分钟后会与整分钟的记录落在同一分钟，所以按分钟去重会丢点 —— 这里不去重，
 *       保证 {@code samples} 与 {@code daily_vitals.heart_rate_samples} 逐日相等。</li>
 * </ul>
 *
 * <p>时间是 {@code yyyy-MM-ddTHH:mm[:ss.SSS]} 的 TEXT，{@code time_zone} 恒为 {@code +0800}
 * —— 字段里存的已经是本地时间，不要再做时区换算。按 {@code start_time >= 当日 AND < 次日}
 * 过滤即可，字符串的字典序就是时间序。
 */
@Component
@Slf4j
public class HeartRateSeriesStore {

    private static final String SELECT_SAMPLES = """
            SELECT start_time, value_num
            FROM health_metric
            WHERE metric_key = 'DYNAMIC_HEART_RATE'
              AND start_time >= :from AND start_time < :to
              AND value_num IS NOT NULL
            ORDER BY start_time
            """;

    /**
     * 当天的运动。{@code run_activity} 的 {@code start_time} 带毫秒（{@code 06:01:38.133}），
     * 与 {@code health_metric} 的分钟精度不同，但同样是本地时间，可以直接用字符串比较。
     */
    private static final String SELECT_ACTIVITIES = """
            SELECT sport, start_time, end_time, distance_meters, avg_heart_rate, max_heart_rate
            FROM run_activity
            WHERE start_time >= :from AND start_time < :to
            ORDER BY start_time
            """;

    private static final String SELECT_LATEST = """
            SELECT MAX(start_time) AS latest FROM health_metric WHERE metric_key = 'DYNAMIC_HEART_RATE'
            """;

    private static final String SELECT_COVERED = """
            SELECT MIN(start_time) AS first_seen, MAX(start_time) AS last_seen
            FROM health_metric WHERE metric_key = 'DYNAMIC_HEART_RATE'
            """;

    private final JdbcClient jdbc;

    public HeartRateSeriesStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 某一天的自动心率采样，按时间升序。
     *
     * <p>用 {@code .list()} 而不是 {@code .stream()}：Hikari 池只有 1 条连接，
     * 流不关闭会把后续查询堵死。
     */
    public List<HeartRateSample> samples(LocalDate date) {
        if (date == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_SAMPLES)
                .param("from", startOf(date))
                .param("to", startOf(date.plusDays(1)))
                .query((rs, rowNum) -> toSample(rs.getString("start_time"), rs.getDouble("value_num")))
                .list().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 当天登记过的运动（含跑步以外的项目）；没有运动时返回空列表。 */
    public List<HeartRateActivity> activities(LocalDate date) {
        if (date == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_ACTIVITIES)
                .param("from", startOf(date))
                .param("to", startOf(date.plusDays(1)))
                .query((rs, rowNum) -> {
                    String start = clock(rs.getString("start_time"));
                    if (start == null) {
                        return null;
                    }
                    double meters = rs.getDouble("distance_meters");
                    return new HeartRateActivity(
                            rs.getString("sport"),
                            start,
                            clock(rs.getString("end_time")),
                            PaceFormat.round(meters / 1000d, 2),
                            (Integer) rs.getObject("avg_heart_rate"),
                            (Integer) rs.getObject("max_heart_rate"));
                }).list().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 有自动心率数据的最后一天；空表返回 {@code empty}。 */
    public Optional<LocalDate> latestDate() {
        return jdbc.sql(SELECT_LATEST)
                .query((rs, rowNum) -> dayOf(rs.getString("latest")))
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    /** 全库第一点与最后一点的日期，给 {@code NO_DATA} 提示用。 */
    public Optional<String> coveredRange() {
        return jdbc.sql(SELECT_COVERED)
                .query((rs, rowNum) -> {
                    String first = rs.getString("first_seen");
                    String last = rs.getString("last_seen");
                    if (first == null || last == null || first.length() < 10 || last.length() < 10) {
                        return null;
                    }
                    return first.substring(0, 10) + " ~ " + last.substring(0, 10);
                }).list().stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /**
     * 把 {@code yyyy-MM-ddTHH:mm[:ss.SSS]} 截成 {@code HH:mm}。
     *
     * <p>秒与毫秒一律丢掉：这张表的粒度本来就是分钟，保留秒只会让同一天的曲线出现
     * {@code 06:35} 与 {@code 06:35:51} 两个看起来重复的点。
     */
    private static HeartRateSample toSample(String raw, double bpm) {
        String time = clock(raw);
        return time == null ? null : new HeartRateSample(time, (int) Math.round(bpm));
    }

    private static String clock(String raw) {
        if (raw == null || raw.length() < 16 || raw.charAt(10) != 'T') {
            return null;
        }
        return raw.substring(11, 16);
    }

    private static LocalDate dayOf(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            log.warn("health_metric.start_time 无法解析，忽略：{}", raw);
            return null;
        }
    }

    /** 区间下界写成 {@code yyyy-MM-ddT00:00}，上界写成次日零点 —— 字典序比较能正确覆盖整天。 */
    private static String startOf(LocalDate date) {
        return date + "T00:00";
    }
}
