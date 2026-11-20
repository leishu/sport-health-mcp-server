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
            Long risingEpochSeconds,
            Long bedTimeEpochSeconds,
            Double avgHeartRate,
            Double avgBreathRate,
            Double snoreFreq) {
    }

    /** 一段连续区间（清醒段）。 */
    public record Span(LocalDateTime start, LocalDateTime end) {

        /** 分钟数，四舍五入到整分钟。 */
        public int minutes() {
            return (int) Math.round(Duration.between(start, end).toSeconds() / 60d);
        }
    }

    /** 一段分期（{@code key} 是原始的 {@code metric_key}）。 */
    public record StageSpan(LocalDateTime start, LocalDateTime end, String key) {
    }

    /** 一段睡眠碎片：设备认定的连续睡眠块。 */
    public record FragmentSpan(LocalDateTime start, LocalDateTime end) {
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
                   avg_hrv, avg_oxygen_saturation, deep_part, fall_asleep_time, rising_time,
                   bed_time, avg_heartrate, avg_breathrate, snore_freq
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

    /** 分期时间线：四个阶段分开取，用 {@code IN} 而不是 {@code LIKE} 才能吃到 {@code idx_health_metric_key}。 */
    private static final String SELECT_STAGE_SPANS = """
            SELECT metric_key, start_time, end_time
            FROM health_metric
            WHERE metric_key IN ('PROFESSIONAL_SLEEP_DEEP', 'PROFESSIONAL_SLEEP_SHALLOW',
                                 'PROFESSIONAL_SLEEP_DREAM', 'PROFESSIONAL_SLEEP_WAKE')
              AND start_time >= :from AND start_time < :to
            ORDER BY start_time
            """;

    private static final String SELECT_NOON_SPANS = """
            SELECT start_time, end_time
            FROM health_metric
            WHERE metric_key = 'PROFESSIONAL_SLEEP_NOON'
              AND start_time >= :from AND start_time < :to
            ORDER BY start_time
            """;

    /**
     * 睡眠碎片（{@code sample_sequence} type=700008）。
     *
     * <p>按记录的 {@code start_time} 过滤：华为把碎片写在该块结束后的几分钟内，
     * 所以用「区间前后各放宽一天」覆盖跨午夜的情形，真正的归属由调用方按区间判定。
     */
    private static final String SELECT_FRAGMENTS = """
            SELECT start_time, time_zone, detail_data
            FROM sample_sequence
            WHERE type_code = 700008 AND start_time >= :from AND start_time < :to
            ORDER BY start_time
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

    /**
     * 时间窗口内的分期段（深/浅/快动/清醒），按开始时间升序。
     *
     * <p>返回的是原始分钟级的段，合并相邻同阶段是分析层的事：这里保持「一行一段」，
     * 免得调用方拿到的边界和数据库里看到的对不上。
     *
     * @param from 含
     * @param to   不含
     */
    public List<StageSpan> stageSpans(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return List.of();
        }
        return jdbc.sql(SELECT_STAGE_SPANS)
                .param("from", from.format(WINDOW_FORMAT))
                .param("to", to.format(WINDOW_FORMAT))
                .query((rs, rowNum) -> {
                    LocalDateTime start = parseTime(rs.getString("start_time"));
                    LocalDateTime end = parseTime(rs.getString("end_time"));
                    if (start == null || end == null || !end.isAfter(start)) {
                        return null;
                    }
                    return new StageSpan(start, end, rs.getString("metric_key"));
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 午睡段（{@code PROFESSIONAL_SLEEP_NOON}），按开始时间升序。
     *
     * <p>不做 08:00~20:00 的筛选：时段判定属于业务口径，放在分析层，这里只负责把区间取全。
     */
    public List<Span> noonSpans(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_NOON_SPANS)
                .param("from", range.from().minusDays(1).toString())
                .param("to", range.to().plusDays(2).toString())
                .query((rs, rowNum) -> {
                    LocalDateTime start = parseTime(rs.getString("start_time"));
                    LocalDateTime end = parseTime(rs.getString("end_time"));
                    return start == null || end == null || !end.isAfter(start) ? null : new Span(start, end);
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 睡眠碎片：{@code sample_sequence} type=700008 的 {@code detail_data} 解出来的连续睡眠块。
     *
     * <p>查询范围前后各放宽一天，跨午夜的碎片（记录时间在零点后的那一半）才不会漏。
     */
    public List<FragmentSpan> fragments(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_FRAGMENTS)
                .param("from", range.from().minusDays(1).toString())
                .param("to", range.to().plusDays(2).toString())
                .query((rs, rowNum) -> decodeFragment(
                        rs.getString("detail_data"),
                        parseTime(rs.getString("start_time")),
                        parseZone(rs.getString("time_zone")))).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 解析一段 {@code sleep_fragment} 的 {@code detail_data}。
     *
     * <p>这是华为的私有二进制（小端），实测布局：{@code [0:4]} 版本号 1、
     * {@code [4:8]} 名字长度、随后是名字 {@code sleep_fragment}、一串元数据，
     * 最后 16 字节里前 8 个是碎片起止的 <b>epoch 秒</b>（两个 uint32，小端），
     * 再往后 8 个是未解读的尾部字段。749 条记录长度全是 72 字节。
     *
     * <p>不硬编码偏移量，而是从末尾取、并做三重校验（名字对不对、起止是否为正、
     * 结束是否落在记录时刻的 ±24 小时内），任何一条不满足就当作没有数据，
     * 免得把别的 type 的二进制当碎片读出一堆 1970 年。
     */
    static FragmentSpan decodeFragment(String hex, LocalDateTime recordTime, java.time.ZoneOffset zone) {
        if (hex == null || hex.length() < 64 || hex.length() % 2 != 0) {
            return null;
        }
        byte[] raw;
        try {
            raw = java.util.HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            log.debug("睡眠碎片 detail_data 不是合法十六进制：{}", hex.length());
            return null;
        }
        if (raw.length < 32) {
            return null;
        }
        int version = readInt(raw, 0);
        int nameLength = readInt(raw, 4);
        if (version != 1 || nameLength <= 0 || 8 + nameLength > raw.length - 16) {
            return null;
        }
        String name = new String(raw, 8, nameLength, java.nio.charset.StandardCharsets.UTF_8);
        if (!"sleep_fragment".equals(name)) {
            log.debug("detail_data 的块名不是 sleep_fragment，跳过：{}", name);
            return null;
        }
        long startSeconds = readUnsignedInt(raw, raw.length - 16);
        long endSeconds = readUnsignedInt(raw, raw.length - 12);
        if (startSeconds <= 0 || endSeconds <= startSeconds) {
            return null;
        }
        if (endSeconds - startSeconds > Duration.ofHours(24).toSeconds()) {
            return null;
        }
        java.time.ZoneOffset offset = zone == null ? java.time.ZoneOffset.ofHours(8) : zone;
        LocalDateTime start = LocalDateTime.ofEpochSecond(startSeconds, 0, offset);
        LocalDateTime end = LocalDateTime.ofEpochSecond(endSeconds, 0, offset);
        if (recordTime != null && Duration.between(start, recordTime).abs().toHours() > 24) {
            log.debug("睡眠碎片区间 {} ~ {} 与记录时刻 {} 相差过大，跳过", start, end, recordTime);
            return null;
        }
        return new FragmentSpan(start, end);
    }

    /** 大端 int32。 */
    private static int readInt(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24) | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
    }

    /** 小端 uint32（epoch 秒）。 */
    private static long readUnsignedInt(byte[] raw, int offset) {
        return ((long) (raw[offset] & 0xFF))
                | ((long) (raw[offset + 1] & 0xFF) << 8)
                | ((long) (raw[offset + 2] & 0xFF) << 16)
                | ((long) (raw[offset + 3] & 0xFF) << 24);
    }

    /** {@code time_zone} 形如 {@code +0800}；解析不了就退回 {@code +08:00}（用户所在地）。 */
    private static java.time.ZoneOffset parseZone(String text) {
        if (text == null || text.isBlank()) {
            return java.time.ZoneOffset.ofHours(8);
        }
        String value = text.trim();
        try {
            return java.time.ZoneOffset.of(value.length() == 5
                    ? value.substring(0, 3) + ":" + value.substring(3)
                    : value);
        } catch (RuntimeException e) {
            log.debug("无法解析时区：{}", text);
            return java.time.ZoneOffset.ofHours(8);
        }
    }

    /** 睡眠数据的整体覆盖区间；两张表都空时返回 {@link Optional#empty()}。 */    public Optional<DateRange> coveredRange() {
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
                epochSeconds(rs, "fall_asleep_time"), epochSeconds(rs, "rising_time"),
                epochSeconds(rs, "bed_time"), RunRows.real(rs, "avg_heartrate"),
                RunRows.real(rs, "avg_breathrate"), RunRows.real(rs, "snore_freq"));
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
