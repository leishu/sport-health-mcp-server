package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 活动量表的只读访问：{@code daily_activity}（一天一行，来自「每分钟运动数据」）。
 *
 * <p>这张表和 {@code daily_vitals} 不一样，<b>没有指标级的稀疏问题</b> —— 368 天全都有值，
 * 因为它由 {@code activity_minute} 按天聚合而来，只要有分钟记录这一天就有行。所以这里
 * 不做「按指标各自算分母」那套，只区分「整天没记录」和「记录很少（可能没戴表 / 导出未完成）」。
 *
 * <p>三个口径坑，都在 {@code DerivedTables.DAILY_ACTIVITY_SQL} 里定死，调用方不要自己再算：
 * <ul>
 *   <li>{@code steps} 会把同一分钟的走路 + 跑步两条记录各算一次，比 App 自报高约 0.8%；</li>
 *   <li>{@code standEvents} 是<b>站立事件条数</b>（{@code sportType=13}），不是分钟数；</li>
 *   <li>{@code caloriesKcal} 是活动热量，不含基础代谢。</li>
 * </ul>
 */
@Component
@Slf4j
public class DailyActivityStore {

    /**
     * 一行原始数据（距离用米、时间用 {@code LocalDate}），把「换算成公里」和「按目标判定」
     * 留给上层，仓储层只负责取数。
     *
     * @param minuteRows 当天有活动记录的分钟条数，用来识别「没戴表 / 导出未完成」
     */
    public record Row(
            LocalDate date,
            int steps,
            int walkSteps,
            int runSteps,
            double distanceMeters,
            double caloriesKcal,
            int activeMinutes,
            int standEvents,
            int minuteRows) {
    }

    private static final String SELECT_DAYS = """
            SELECT activity_date, steps, walk_steps, run_steps, distance_meters, calories_kcal,
                   active_minutes, stand_events, minute_rows
            FROM daily_activity
            WHERE activity_date >= :from AND activity_date <= :to
            ORDER BY activity_date
            """;

    private static final String SELECT_COVERED_RANGE = """
            SELECT MIN(activity_date) AS first_day, MAX(activity_date) AS last_day
            FROM daily_activity
            """;

    private final JdbcClient jdbc;

    public DailyActivityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 区间内的逐日活动，按日期升序。
     *
     * <p>用 {@code .list()} 而不是 {@code .stream()}：Hikari 池只有 1 条连接，
     * 流不关就堵死后续查询。
     */
    public List<Row> days(DateRange range) {
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

    private static Row toRow(ResultSet rs) throws SQLException {
        LocalDate date = parseDate(rs.getString("activity_date"));
        if (date == null) {
            return null;
        }
        return new Row(
                date,
                rs.getInt("steps"),
                rs.getInt("walk_steps"),
                rs.getInt("run_steps"),
                rs.getDouble("distance_meters"),
                rs.getDouble("calories_kcal"),
                rs.getInt("active_minutes"),
                rs.getInt("stand_events"),
                rs.getInt("minute_rows"));
    }

    /** {@code activity_date} 是 {@code yyyy-MM-dd} 的 TEXT，解析失败说明库被外部改过，跳过这一行。 */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DateParser.parseDate(value);
        } catch (RuntimeException e) {
            log.warn("daily_activity.activity_date 无法解析，跳过：{}", value);
            return null;
        }
    }
}
