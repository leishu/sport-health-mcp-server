package org.example.sporthealth.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.Spo2LowEvent;
import org.example.sporthealth.domain.VitalsSample;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * HRV / 压力 / 血氧三个「稀疏单点」指标的只读访问，外加睡眠窗口。
 *
 * <p>这三个指标都在 {@code health_metric} 里，但都不是连续曲线：
 * <ul>
 *   <li>{@code HEART_RATE_VARIABILITY}：9 885 点 / 152 天，约 10 分钟一条，值域 11~158 ms；</li>
 *   <li>{@code STRESS}：4 837 点 / 152 天，约 30 分钟一条，值域 15~53 分；</li>
 *   <li>{@code BLOOD_OXYGEN_SATURATION}：<b>只有 558 点 / 12 天</b>，2026-06-12~17 与
 *       2026-08-03~08 两段，之后设备不再上报。</li>
 * </ul>
 *
 * <p><b>时间格式不统一，必须解析成 {@link LocalDateTime} 再比较</b>：
 * {@code health_metric.start_time} 是 {@code 2026-11-09T22:12}（{@code T} 分隔），
 * {@code sleep_session.session_start} 是 {@code 2026-11-09 22:12:00}（空格分隔）。
 * 直接拿字符串比会把 {@code T} 当成大于空格的字符，导致「同一天的记录全部被排除」——
 * 这不是假设，是实测踩过的坑，看起来数字还挺合理，只是全错。
 */
@Component
@Slf4j
public class VitalsMetricStore {

    /** 一个睡眠窗口，时间已解析成 {@link LocalDateTime}，可直接与样本比较。 */
    public record SleepWindow(LocalDate sleepDate, LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 带完整时刻的采样。
     *
     * <p>HRV 必须按时段分组（睡眠 / 日间），只留 {@code HH:mm} 不够 —— 还要知道它属于哪一天、
     * 以及能不能落进某个睡眠窗口。压力和血氧用不上这个，它们只需要 {@link VitalsSample}。
     *
     * @param at    完整本地时刻
     * @param clock 展示用时刻 {@code HH:mm}
     * @param value 测量值
     */
    public record TimedSample(LocalDateTime at, String clock, double value) {
    }

    private static final String SELECT_HRV = """
            SELECT start_time, value_num
            FROM health_metric
            WHERE metric_key = 'HEART_RATE_VARIABILITY'
              AND start_time >= :from AND start_time < :to
              AND value_num IS NOT NULL
            ORDER BY start_time
            """;

    private static final String SELECT_STRESS = """
            SELECT start_time, value_num
            FROM health_metric
            WHERE metric_key = 'STRESS'
              AND start_time >= :from AND start_time < :to
              AND value_num IS NOT NULL
            ORDER BY start_time
            """;

    private static final String SELECT_SPO2 = """
            SELECT start_time, value_num
            FROM health_metric
            WHERE metric_key = 'BLOOD_OXYGEN_SATURATION'
              AND start_time >= :from AND start_time < :to
              AND value_num IS NOT NULL
            ORDER BY start_time
            """;

    /**
     * 低氧提醒。{@code BLOOD_OXYGEN_REMIND}（type=19）与 {@code LOW_BLOOD_OXYGEN}（type=400022）
     * 是同一时刻的同一件事，只取后者 —— 它的 {@code value_text} 里带阈值。
     */
    private static final String SELECT_LOW_OXYGEN = """
            SELECT start_time, value_text
            FROM health_metric
            WHERE metric_key = 'LOW_BLOOD_OXYGEN'
              AND start_time >= :from AND start_time < :to
            ORDER BY start_time
            """;

    /**
     * 区间内的夜睡会话，每天只留一条。
     *
     * <p>同一天偶尔有两条 {@code NIGHT} 会话（短睡被单独记了一段），取 {@code asleep_minutes}
     * 大的那条；相等时取更早开始的。不这样筛，同一天的样本会被两条会话重复计入。
     */
    private static final String SELECT_SLEEP_WINDOWS = """
            SELECT s.sleep_date, s.session_start, s.session_end
            FROM sleep_session s
            WHERE s.sleep_type = 'NIGHT'
              AND s.sleep_date >= :from AND s.sleep_date <= :to
              AND s.session_start = (
                    SELECT o.session_start FROM sleep_session o
                    WHERE o.sleep_type = 'NIGHT' AND o.sleep_date = s.sleep_date
                    ORDER BY o.asleep_minutes DESC, o.session_start DESC
                    LIMIT 1)
            ORDER BY s.sleep_date
            """;

    private static final String SELECT_SPAN = """
            SELECT MIN(start_time) AS first_seen, MAX(start_time) AS last_seen
            FROM health_metric WHERE metric_key = :metric
            """;

    private final JdbcClient jdbc;

    public VitalsMetricStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 区间内的 HRV 原始采样（带完整时刻），按时间升序。 */
    public List<TimedSample> hrv(DateRange range) {
        return timed(SELECT_HRV, range);
    }

    /** 区间内的压力原始采样，按时间升序。单日查询，带 {@code HH:mm} 就够。 */
    public List<VitalsSample> stress(DateRange range) {
        return samples(SELECT_STRESS, range);
    }

    /** 区间内的血氧原始采样（带完整时刻），按时间升序 —— 跨多天查询要靠它按日期归组。 */
    public List<TimedSample> spo2(DateRange range) {
        return timed(SELECT_SPO2, range);
    }

    /**
     * 区间内的夜睡窗口。
     *
     * <p>起始日往前多读一天是调用方的事：查 {@code 2026-06-13} 这一夜，
     * 会话其实从 {@code 2026-06-12} 晚上就开始了。
     */
    public List<SleepWindow> sleepWindows(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_SLEEP_WINDOWS)
                .param("from", range.from().toString())
                .param("to", range.to().toString())
                .query((rs, rowNum) -> {
                    LocalDate date = dayOf(rs.getString("sleep_date"));
                    LocalDateTime start = dateTime(rs.getString("session_start"));
                    LocalDateTime end = dateTime(rs.getString("session_end"));
                    if (date == null || start == null || end == null || !end.isAfter(start)) {
                        return null;
                    }
                    return new SleepWindow(date, start, end);
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 区间内设备下发的血氧偏低提醒。 */
    public List<Spo2LowEvent> lowOxygenEvents(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_LOW_OXYGEN)
                .param("from", startOf(range.from()))
                .param("to", startOf(range.to().plusDays(1)))
                .query((rs, rowNum) -> {
                    String clock = clock(rs.getString("start_time"));
                    if (clock == null) {
                        return null;
                    }
                    Double threshold = thresholdOf(rs.getString("value_text"));
                    Double value = valueOf(rs.getString("value_text"));
                    if (value == null) {
                        return null;
                    }
                    return new Spo2LowEvent(clock, (int) Math.round(value), threshold);
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 某指标在全库的第一条与最后一条日期，给默认区间与 {@code NO_DATA} 提示用。 */
    public Optional<DateRange> span(String metric) {
        return jdbc.sql(SELECT_SPAN)
                .param("metric", metric)
                .query((rs, rowNum) -> {
                    LocalDate first = dayOf(rs.getString("first_seen"));
                    LocalDate last = dayOf(rs.getString("last_seen"));
                    return first == null || last == null ? null : new DateRange(first, last);
                }).list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** 某指标在全库的最后一条日期。 */
    public Optional<LocalDate> latestDate(String metric) {
        return span(metric).map(DateRange::to);
    }

    /** 只给 {@code HH:mm} 的实现，给单日查询用（压力）。 */
    private List<VitalsSample> samples(String sql, DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(sql)
                .param("from", startOf(range.from()))
                .param("to", startOf(range.to().plusDays(1)))
                .query((rs, rowNum) -> {
                    String clock = clock(rs.getString("start_time"));
                    if (clock == null) {
                        return null;
                    }
                    return new VitalsSample(clock, rs.getDouble("value_num"));
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 带完整时刻的实现，给需要按日期/时段分组的多日查询用（HRV、血氧）。 */
    private List<TimedSample> timed(String sql, DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(sql)
                .param("from", startOf(range.from()))
                .param("to", startOf(range.to().plusDays(1)))
                .query((rs, rowNum) -> {
                    String raw = rs.getString("start_time");
                    LocalDateTime at = dateTime(raw);
                    String clock = clock(raw);
                    if (at == null || clock == null) {
                        return null;
                    }
                    return new TimedSample(at, clock, rs.getDouble("value_num"));
                }).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 从 {@code value_text} 里抠 {@code lowSpO2Threshold}。
     *
     * <p>不引 JSON 库：这里只需要一个数字，用正则在原文上找最快，
     * 解析不出来就返回 {@code null}，不影响主流程。
     */
    private static Double thresholdOf(String text) {
        return extractNumber(text, "lowSpO2Threshold");
    }

    private static Double valueOf(String text) {
        return extractNumber(text, "bloodOxygenRemind");
    }

    private static Double extractNumber(String text, String key) {
        if (text == null) {
            return null;
        }
        String needle = "\"" + key + "\":";
        int at = text.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int i = at + needle.length();
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
            i++;
        }
        if (i == start) {
            return null;
        }
        try {
            return Double.parseDouble(text.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code yyyy-MM-ddTHH:mm[:ss[.SSS]]} 截成 {@code HH:mm}。 */
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
            log.warn("日期无法解析，忽略：{}", raw);
            return null;
        }
    }

    /**
     * 两种分隔符都吃：{@code 2026-11-09T22:12} 与 {@code 2026-11-09 22:12:00}。
     *
     * <p>毫秒尾巴要先截掉（{@code 19:20:11.747}）—— {@link DateParser} 的格式表里没有带毫秒的那一种，
     * 不截会整条丢掉。
     */
    private static LocalDateTime dateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replace('T', ' ');
        int dot = text.indexOf('.');
        if (dot > 0) {
            text = text.substring(0, dot);
        }
        try {
            return DateParser.parseDateTime(text);
        } catch (RuntimeException e) {
            log.warn("时刻无法解析，忽略：{}", raw);
            return null;
        }
    }

    /** 区间下界写成 {@code yyyy-MM-ddT00:00}，与 {@code health_metric} 的格式一致。 */
    private static String startOf(LocalDate date) {
        return date + "T00:00";
    }
}
