package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 睡眠相关的只读访问：{@code sleep_session}、{@code sleep_report} 与 {@code health_metric} 里的睡眠分期。
 *
 * <p>三张表的分工（这是本类存在的理由，也是口径容易跑偏的地方）：
 * <ul>
 *   <li>{@code sleep_session}：逐分钟分期粘成的会话，覆盖全部历史，带深睡/浅睡/REM/清醒时长；</li>
 *   <li>{@code sleep_report}：官方睡眠报告，2026-06-13 起才有，评分、效率、HRV、血氧、潜伏期都在这；</li>
 *   <li>{@code health_metric}：分期的原始区间，用来还原「醒了几次」——报告里的 {@code wakeCount}
 *       全是 0（设备没上报），只有原始清醒段是可信的。</li>
 * </ul>
 *
 * <p><b>时间格式不统一</b>：{@code sleep_session} 是 {@code yyyy-MM-dd HH:mm:ss}（空格），
 * {@code sleep_report} 与 {@code health_metric} 是 {@code yyyy-MM-ddTHH:mm}（T、无秒）。
 * 字符串直接比较会把 {@code T} 当成比空格大的字符，同一天内会比错，所以跨格式一律先解析成
 * {@link LocalDateTime}；只有同格式（如 {@code health_metric} 自己跟自己）才用字符串前缀比较，
 * 那样才能命中 {@code idx_health_metric_key}。
 *
 * <p>所有查询都用 {@code .list()} 而不是 {@code .stream()}：Hikari 池只有 1 条连接，
 * 流不关就把后面的查询全堵死。
 */
@Component
@Slf4j
public class SleepStore {

    /** 夜间会话：窗口 + 派生表已经算好的分期时长。 */
    public record NightSession(
            LocalDate date,
            LocalDateTime start,
            LocalDateTime end,
            int inBedMinutes,
            int asleepMinutes,
            int deepMinutes,
            int lightMinutes,
            int remMinutes,
            int awakeMinutes) {
    }

    /** 有评分的官方睡眠报告（评分 ≤ 0 的行是午睡或无效记录，查询阶段就排除）。 */
    public record ScoredReport(
            LocalDate date,
            LocalDateTime start,
            LocalDateTime end,
            Double score,
            Double efficiency,
            Double latency,
            Double hrv,
            Double spo2,
            Double deepPart,
            Long fallAsleepEpochSeconds,
            Long risingEpochSeconds) {
    }

    /** 一段连续区间（清醒段）。 */
    public record Span(LocalDateTime start, LocalDateTime end) {

        /** 分钟数，四舍五入到整分钟。 */
        public int minutes() {
            return (int) Math.round(Duration.between(start, end).toSeconds() / 60d);
        }
    }

    private static final String SELECT_NIGHT_SESSIONS = """
            SELECT session_start, session_end, sleep_date, in_bed_minutes, asleep_minutes,
                   deep_minutes, light_minutes, rem_minutes, awake_minutes
            FROM sleep_session
            WHERE sleep_type = 'NIGHT' AND sleep_date >= :from AND sleep_date <= :to
            ORDER BY session_start
            """;

    private static final String SELECT_SCORED_REPORTS = """
            SELECT sleep_date, start_time, end_time, sleep_score, sleep_efficiency, sleep_latency,
                   avg_hrv, avg_oxygen_saturation, deep_part, fall_asleep_time, rising_time
            FROM sleep_report
            WHERE sleep_date >= :from AND sleep_date <= :to AND sleep_score > 0
            ORDER BY start_time
            """;

    /** 清醒段的时间上界用「次日」的日期前缀，靠 {@code idx_health_metric_key} 走索引。 */
    private static final String SELECT_WAKE_SPANS = """
            SELECT start_time, end_time
            FROM health_metric
            WHERE metric_key = 'PROFESSIONAL_SLEEP_WAKE'
              AND start_time >= :from AND start_time < :toExclusive
            ORDER BY start_time
            """;

    private static final String SELECT_STAGE_MINUTES = """
            SELECT metric_key,
                   CAST(ROUND(SUM((julianday(end_time) - julianday(start_time)) * 1440.0)) AS INTEGER) AS minutes
            FROM health_metric
            WHERE metric_key LIKE 'PROFESSIONAL_SLEEP_%'
              AND start_time >= :from AND end_time <= :to
            GROUP BY metric_key
            """;

    /** 聚合查询，空表时也返回一行（两列都是 NULL），所以按「一行或零行」处理就够了。 */
    private static final String SELECT_COVERED_RANGE = """
            SELECT MIN(d) AS first_day, MAX(d) AS last_day
            FROM (SELECT sleep_date AS d FROM sleep_session
                  UNION
                  SELECT sleep_date AS d FROM sleep_report WHERE sleep_score > 0)
            """;

    /** 分段查询用的时间格式：与 {@code health_metric.start_time} 一致（T、到分钟）。 */
    private static final java.time.format.DateTimeFormatter WINDOW_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final JdbcClient jdbc;

    public SleepStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 区间内的夜间会话，按开始时间升序。
     *
     * <p>同一天可能出现两条 {@code NIGHT}（下午补觉没被标成午睡，2026-01-11 就是这种情况），
     * 这里不做取舍也不丢数据，由调用方按「睡得更久」挑一条。
     */
    public List<NightSession> nightSessions(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_NIGHT_SESSIONS)
                .param("from", range.from().toString())
                .param("to", range.to().toString())
                .query((rs, rowNum) -> toNightSession(rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 区间内有评分的报告，按开始时间升序。 */
    public List<ScoredReport> scoredReports(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_SCORED_REPORTS)
                .param("from", range.from().toString())
                .param("to", range.to().toString())
                .query((rs, rowNum) -> toScoredReport(rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 区间内的清醒段，按开始时间升序。
     *
     * <p>查询范围往前多取一天：区间第一天的夜晚可能从前一天傍晚开始。
     */
    public List<Span> wakeSpans(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_WAKE_SPANS)
                .param("from", range.from().minusDays(1).toString())
                .param("toExclusive", range.to().plusDays(1).toString())
                .query((rs, rowNum) -> {
                    LocalDateTime start = parseTime(rs.getString("start_time"));
                    LocalDateTime end = parseTime(rs.getString("end_time"));
                    return start == null || end == null || !end.isAfter(start) ? null : new Span(start, end);
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 某个时间窗口内的分期分钟数，键是 {@code metric_key}（{@code PROFESSIONAL_SLEEP_DEEP} 等）。
     *
     * <p>给「有报告、但没有夜间会话」的夜晚用：这类夜晚的会话被午睡粘住、整段标成了 NAP，
     * 用报告自己的起止时刻把分期重新框一遍，比按睡眠效率反推可靠得多
     * （实测 2026-07-16 的效率 73% × 282 分钟 = 206，而窗口内深睡 84 + 浅睡 148 + 快速眼动 43 = 275）。
     *
     * <p>边界用报告的时间格式（{@code yyyy-MM-ddTHH:mm}）传进来，与 {@code health_metric}
     * 自己的格式一致，字符串比较才是对的，也才能用上索引。
     */
    public Map<String, Integer> stageMinutesInWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            return Map.of();
        }
        Map<String, Integer> map = new HashMap<>();
        jdbc.sql(SELECT_STAGE_MINUTES)
                .param("from", windowStart.format(WINDOW_FORMAT))
                .param("to", windowEnd.format(WINDOW_FORMAT))
                .query((rs, rowNum) -> Map.entry(rs.getString("metric_key"), rs.getInt("minutes"))).list()
                .forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        return map;
    }

    /** 睡眠数据的整体覆盖区间；两张表都空时返回 {@link Optional#empty()}。 */
    public Optional<DateRange> coveredRange() {
        return jdbc.sql(SELECT_COVERED_RANGE)
                .query((rs, rowNum) -> {
                    LocalDate first = parseDate(rs.getString("first_day"));
                    LocalDate last = parseDate(rs.getString("last_day"));
                    return first == null || last == null ? null : new DateRange(first, last);
                }).list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static NightSession toNightSession(ResultSet rs) throws SQLException {
        LocalDate day = parseDate(rs.getString("sleep_date"));
        LocalDateTime start = parseTime(rs.getString("session_start"));
        LocalDateTime end = parseTime(rs.getString("session_end"));
        if (day == null || start == null || end == null || !end.isAfter(start)) {
            log.debug("跳过无法定位时间轴的 sleep_session 行：{} / {}", rs.getString("sleep_date"),
                    rs.getString("session_start"));
            return null;
        }
        return new NightSession(day, start, end,
                rs.getInt("in_bed_minutes"), rs.getInt("asleep_minutes"),
                rs.getInt("deep_minutes"), rs.getInt("light_minutes"),
                rs.getInt("rem_minutes"), rs.getInt("awake_minutes"));
    }

    private static ScoredReport toScoredReport(ResultSet rs) throws SQLException {
        LocalDate day = parseDate(rs.getString("sleep_date"));
        LocalDateTime start = parseTime(rs.getString("start_time"));
        LocalDateTime end = parseTime(rs.getString("end_time"));
        if (day == null || start == null || end == null || !end.isAfter(start)) {
            log.debug("跳过无法定位时间轴的 sleep_report 行：{} / {}", rs.getString("sleep_date"),
                    rs.getString("start_time"));
            return null;
        }
        return new ScoredReport(day, start, end,
                RunRows.real(rs, "sleep_score"), RunRows.real(rs, "sleep_efficiency"),
                RunRows.real(rs, "sleep_latency"), RunRows.real(rs, "avg_hrv"),
                RunRows.real(rs, "avg_oxygen_saturation"), RunRows.real(rs, "deep_part"),
                epochSeconds(rs, "fall_asleep_time"), epochSeconds(rs, "rising_time"));
    }

    /** epoch 秒；0 与负数都是「没记」，返回 null 而不是 1970 年。 */
    private static Long epochSeconds(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() || value <= 0 ? null : value;
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateParser.parseDate(text.trim());
        } catch (RuntimeException e) {
            log.debug("无法解析睡眠日期：{}", text);
            return null;
        }
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateParser.parseDateTime(text.trim());
        } catch (RuntimeException e) {
            log.debug("无法解析睡眠时刻：{}", text);
            return null;
        }
    }
}
