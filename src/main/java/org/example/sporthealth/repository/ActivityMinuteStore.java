package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.support.DateRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 分钟级活动表 {@code activity_minute} 的只读访问，带<b>同一分钟去重</b>。
 *
 * <p>这张表的主键是 {@code (start_time, sport_type)}，看着像「一分钟一行」，实际不是：
 * 全库 2 208 个分钟有<b>两条</b>记录，而且<b>无一例外是两台设备同时记的同一分钟</b>
 * （没有任何一条是同设备、同分钟的两种运动类型）。第三台设备还把「走路」报成
 * {@code sportType=2}、另两台报 {@code 5}，于是这一分钟被算了两遍。
 * 直接 {@code SUM(steps)} 会让全库步数虚高 3.45%，个别日子（2026-06-14）虚高 20%。
 *
 * <p>所以取分钟明细一律走 {@link #MERGED_MINUTE_SQL}：<b>同一分钟只留一条</b>，
 * 按「步数 → 距离 → 热量」降序挑，认的是记录更完整的那台设备；中高强度与站立标记则取并集
 * （这两列有大量 NULL，NULL 是「没上报」不是「否」）。唯一的例外是站立事件
 * （{@code sportType=13}）—— 它是整点触发的独立事件，可能和同一分钟的走路撞上
 * （268 个分钟），必须分开保留，否则会把站立吃掉。
 *
 * <p>这段 SQL 同时被 {@link DerivedTables#DAILY_ACTIVITY_SQL} 复用，
 * 保证「分钟明细」和「每天步数」是同一份数据算出来的，两个工具不会对不上。
 */
@Component
@Slf4j
public class ActivityMinuteStore {

    /**
     * 去重后的分钟源表。列含义与 {@code activity_minute} 一致，额外一列
     * {@code source_rows} 表示这一分钟原本有几条记录（&gt;1 就是跨设备重复）。
     *
     * <p>取值规则：<b>步数 / 距离 / 热量 / 类型 / 设备</b>都取「步数最大的那条」，
     * 因为两台设备记的是同一分钟，步数多的一般是记录更完整的那台；
     * <b>中高强度（{@code is_intensity}）取并集</b> —— 这一列有大量 NULL
     * （走路记录里 71% 没有 {@code isIntensity}），NULL 是「没上报」而不是「否」，
     * 只认一台设备会平白丢掉 3.4% 的中高强度分钟。
     *
     * <p>刻意<b>不输出 {@code is_stand}</b>：那一列和「站立事件」不是一回事
     * （634 条走路、91 条跑步也被标了 {@code is_stand=1}），站立事件只能认
     * {@code sport_type = 13}，否则一天会多算出 5 次。
     *
     * <p>两层嵌套是必要的：{@code ROW_NUMBER()} 的 {@code PARTITION BY} 要引用
     * 「站立 / 非站立」这个派生分组，只能先在内层 SELECT 里算好再分区
     * （SQLite 不允许窗口函数直接引用同层的列别名）。
     */
    static final String MERGED_MINUTE_SQL = """
            SELECT start_time, end_time, sport_type, record_day, steps, distance_meters,
                   calories_kcal, duration_minutes, intensity_any AS is_intensity, device_code, source_rows
            FROM (
                SELECT m.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY start_time, grp
                           ORDER BY COALESCE(steps, 0) DESC, COALESCE(distance_meters, 0) DESC,
                                    COALESCE(calories_kcal, 0) DESC, sport_type
                       ) AS rn,
                       COUNT(*) OVER (PARTITION BY start_time, grp) AS source_rows,
                       MAX(is_intensity) OVER (PARTITION BY start_time, grp) AS intensity_any
                FROM (
                    SELECT start_time, end_time, sport_type, record_day, steps, distance_meters,
                           calories_kcal, duration_minutes, is_intensity, device_code,
                           CASE WHEN sport_type = 13 THEN 13 ELSE 0 END AS grp
                    FROM activity_minute
                    WHERE length(CAST(record_day AS TEXT)) = 8
                ) m
            )
            WHERE rn = 1
            """;

    private static final String SELECT_MINUTES_OF_DAY = "SELECT * FROM (" + MERGED_MINUTE_SQL + ") "
            + "WHERE start_time >= :from AND start_time < :to ORDER BY start_time, sport_type";

    private static final String SELECT_COVERED_RANGE = """
            SELECT MIN(record_day) AS first_day, MAX(record_day) AS last_day
            FROM activity_minute
            WHERE length(CAST(record_day AS TEXT)) = 8
            """;

    /**
     * 一分钟后的一条记录（已去重）。
     *
     * @param start          这一分钟的起点（本地墙上时间，秒恒为 0）
     * @param end            结束时间，正常是起点 + 1 分钟
     * @param sportType      原始 {@code sport_type}：2/5 走路、4 跑步、13 站立事件
     * @param durationMinutes 覆盖的分钟数，站立事件恒为 0
     * @param intensity      官方是否标记为中高强度（可能为 null，落库时已折成 false）
     * @param stand          是否<b>站立事件</b>：只看 {@code sport_type = 13}。
     *                       {@code is_stand} 列不是这个意思（走路 / 跑步也会被标），不能替代
     * @param sourceRows     这一分钟原本有几条记录；&gt;1 表示两台设备同时记了
     */
    public record MinuteRow(
            LocalDateTime start,
            LocalDateTime end,
            int sportType,
            int steps,
            double distanceMeters,
            double caloriesKcal,
            int durationMinutes,
            boolean intensity,
            boolean stand,
            String deviceCode,
            int sourceRows) {
    }

    private final JdbcClient jdbc;

    public ActivityMinuteStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 某一天的分钟明细，按时间升序。{@code start_time} 是 {@code yyyy-MM-ddTHH:mm}
     * 的定长文本，直接做字符串区间比较即可（比 {@code date()} 函数快，也用得上主键）。
     */
    public List<MinuteRow> minutes(LocalDate date) {
        if (date == null) {
            return List.of();
        }
        String from = date + "T00:00";
        String to = date.plusDays(1) + "T00:00";
        return jdbc.sql(SELECT_MINUTES_OF_DAY)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> toRow(rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 全库最早与最晚的活动日；空表返回 {@code empty}。 */
    public Optional<DateRange> coveredRange() {
        return jdbc.sql(SELECT_COVERED_RANGE)
                .query((rs, rowNum) -> {
                    LocalDate from = parseDay(rs.getInt("first_day"));
                    LocalDate to = parseDay(rs.getInt("last_day"));
                    return from == null || to == null ? null : new DateRange(from, to);
                }).list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static MinuteRow toRow(ResultSet rs) throws SQLException {
        LocalDateTime start = parseTime(rs.getString("start_time"));
        if (start == null) {
            return null;
        }
        String endText = rs.getString("end_time");
        LocalDateTime end = endText == null ? start.plusMinutes(1) : parseTime(endText);
        int sportType = rs.getInt("sport_type");
        return new MinuteRow(
                start,
                end == null ? start.plusMinutes(1) : end,
                sportType,
                rs.getInt("steps"),
                rs.getDouble("distance_meters"),
                rs.getDouble("calories_kcal"),
                rs.getInt("duration_minutes"),
                rs.getInt("is_intensity") == 1,
                sportType == 13,
                rs.getString("device_code"),
                Math.max(1, rs.getInt("source_rows")));
    }

    /** {@code record_day} 是整数（20261109），非法值说明库被外部改过。 */
    private static LocalDate parseDay(int recordDay) {
        if (recordDay < 19000000 || recordDay > 99999999) {
            return null;
        }
        try {
            return LocalDate.of(recordDay / 10000, recordDay / 100 % 100, recordDay % 100);
        } catch (RuntimeException e) {
            log.warn("activity_minute.record_day 越界，跳过：{}", recordDay);
            return null;
        }
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (RuntimeException e) {
            log.warn("activity_minute.start_time 无法解析，跳过：{}", value);
            return null;
        }
    }
}
