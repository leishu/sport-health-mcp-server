package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.VitalsAvailability;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 体征表的只读访问：{@code daily_vitals}（一天一行的心率/HRV/压力/最大摄氧量）。
 *
 * <p><b>这张表不是「每天都有全部指标」</b>，四类指标的覆盖率差得很远（全库实测）：
 * <ul>
 *   <li>静息心率 / 全天心率：2025-11-08 起 368 天，中间没有缺口；</li>
 *   <li>HRV / 压力：2026-06-12 起 152 天，比静息心率晚半年多；</li>
 *   <li>最大摄氧量：104 天，但 2025-09-21 之后断到 2026-11-01 才有下一条 ——
 *       那 14 个月里跑了 185 次却没有一条 VO2MAX，是设备停止上报，不是没运动。</li>
 * </ul>
 * 所以凡是「均值 / 覆盖率」都要按指标各自算分母，不能拿行数当分母。
 *
 * <p>{@code heart_rate_samples = 0} 的行是只有 VO2max 的早期记录（100 天），
 * 这类行的 {@code avg/min/max_heart_rate} 全是 NULL，判断「有没有当天心率」要看这一列，
 * 不能看 {@code heart_date} 是否存在。
 */
@Component
@Slf4j
public class VitalsStore {

    /** 区间末端的四个基线均值，给「当前值相对近期水平」用。 */
    public record Baselines(
            Double restingHeartRate,
            Double hrv,
            Double stress,
            Double vo2max) {
    }

    private static final String SELECT_DAYS = """
            SELECT heart_date, resting_heart_rate, hrv_rmssd, avg_heart_rate, min_heart_rate,
                   max_heart_rate, heart_rate_samples, stress_avg, stress_max, vo2max
            FROM daily_vitals
            WHERE heart_date >= :from AND heart_date <= :to
            ORDER BY heart_date
            """;

    /**
     * 五个指标的全库范围，一次查询拿全。
     *
     * <p>每个分支都必须带自己的 {@code IS NOT NULL}：只在 SELECT 里写 {@code COUNT(col)} 的话，
     * {@code MIN/MAX(heart_date)} 仍然会扫全表，把「某指标从没出现过的那些天」也算进跨度里
     * —— 实测会得到「VO2max 到 2026-11-10 都有值」这种假象。
     *
     * <p>{@code heartRate} 用 {@code heart_rate_samples > 0} 判定：早期那 100 行只有 VO2max，
     * 三个心率列都是 NULL，拿它们算「心率覆盖天数」会把覆盖率虚高。
     */
    private static final String SELECT_SPANS = """
            SELECT 'restingHeartRate' AS metric, MIN(heart_date) AS first_day,
                   MAX(heart_date) AS last_day, COUNT(resting_heart_rate) AS day_count
            FROM daily_vitals WHERE resting_heart_rate IS NOT NULL
            UNION ALL
            SELECT 'hrv', MIN(heart_date), MAX(heart_date), COUNT(hrv_rmssd)
            FROM daily_vitals WHERE hrv_rmssd IS NOT NULL
            UNION ALL
            SELECT 'stress', MIN(heart_date), MAX(heart_date), COUNT(stress_avg)
            FROM daily_vitals WHERE stress_avg IS NOT NULL
            UNION ALL
            SELECT 'vo2max', MIN(heart_date), MAX(heart_date), COUNT(vo2max)
            FROM daily_vitals WHERE vo2max IS NOT NULL
            UNION ALL
            SELECT 'heartRate', MIN(heart_date), MAX(heart_date), COUNT(heart_rate_samples)
            FROM daily_vitals WHERE heart_rate_samples > 0
            """;

    /** 四个基线均值一次算完，避免四次往返。没有数据的指标返回 NULL。 */
    private static final String SELECT_BASELINES = """
            SELECT AVG(resting_heart_rate) AS resting, AVG(hrv_rmssd) AS hrv,
                   AVG(stress_avg) AS stress, AVG(vo2max) AS vo2max
            FROM daily_vitals
            WHERE heart_date >= :from AND heart_date <= :to
            """;

    private static final String SELECT_COVERED_RANGE = """
            SELECT MIN(heart_date) AS first_day, MAX(heart_date) AS last_day
            FROM daily_vitals
            """;

    private final JdbcClient jdbc;

    public VitalsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 区间内的逐日体征，按日期升序。
     *
     * <p>用 {@code .list()} 而不是 {@code .stream()}：Hikari 池只有 1 条连接，流不关就堵死后续查询。
     */
    public List<DailyVitals> days(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_DAYS)
                .param("from", range.from().toString())
                .param("to", range.to().toString())
                .query((rs, rowNum) -> toRow(rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 五个指标各自的全库可用范围；从没出现过的指标不返回。 */
    public List<VitalsAvailability> availability() {
        return jdbc.sql(SELECT_SPANS)
                .query((rs, rowNum) -> {
                    LocalDate from = parseDate(rs.getString("first_day"));
                    LocalDate to = parseDate(rs.getString("last_day"));
                    if (from == null || to == null) {
                        return null;
                    }
                    return new VitalsAvailability(rs.getString("metric"), from, to, rs.getInt("day_count"));
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 区间内的四个指标均值；区间里一个值都没有时对应字段为 null。 */
    public Baselines baselines(DateRange range) {
        if (range == null) {
            return new Baselines(null, null, null, null);
        }
        return jdbc.sql(SELECT_BASELINES)
                .param("from", range.from().toString())
                .param("to", range.to().toString())
                .query((rs, rowNum) -> new Baselines(
                        (Double) rs.getObject("resting"),
                        (Double) rs.getObject("hrv"),
                        (Double) rs.getObject("stress"),
                        (Double) rs.getObject("vo2max"))).single();
    }

    /** 全库第一天与最后一天；空表返回 {@code empty}。 */
    public Optional<DateRange> coveredRange() {
        return jdbc.sql(SELECT_COVERED_RANGE)
                .query((rs, rowNum) -> {
                    LocalDate from = parseDate(rs.getString("first_day"));
                    LocalDate to = parseDate(rs.getString("last_day"));
                    return from == null || to == null ? null : new DateRange(from, to);
                }).list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static DailyVitals toRow(ResultSet rs) throws SQLException {
        LocalDate date = parseDate(rs.getString("heart_date"));
        if (date == null) {
            return null;
        }
        return new DailyVitals(
                date,
                (Integer) rs.getObject("resting_heart_rate"),
                (Double) rs.getObject("hrv_rmssd"),
                (Double) rs.getObject("avg_heart_rate"),
                (Integer) rs.getObject("min_heart_rate"),
                (Integer) rs.getObject("max_heart_rate"),
                rs.getInt("heart_rate_samples"),
                (Double) rs.getObject("stress_avg"),
                (Integer) rs.getObject("stress_max"),
                (Double) rs.getObject("vo2max"));
    }

    /** {@code heart_date} 是 {@code yyyy-MM-dd} 的 TEXT，解析失败说明库被外部改过，跳过这一行。 */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DateParser.parseDate(value);
        } catch (RuntimeException e) {
            log.warn("daily_vitals.heart_date 无法解析，跳过：{}", value);
            return null;
        }
    }
}
