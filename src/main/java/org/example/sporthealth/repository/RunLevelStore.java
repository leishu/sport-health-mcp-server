package org.example.sporthealth.repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.RunLevel;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code run_level} 的只读访问：华为官方跑力、五档配速与官方成绩。
 *
 * <p>这张表由派生表重建写入，每天一行快照，行数很小（一年 368 行），
 * 所以不做缓存、也不在 SQL 里做区间聚合，直接按 {@code record_day} 倒序取。
 *
 * <p>{@code record_day} 是华为导出原文（{@code yyyyMMdd}），
 * 比较与排序都按字符串进行，与库里的主键顺序一致；解析时额外兼容 ISO 日期，
 * 避免以后换导出格式时整张表读不出数据。
 */
@Component
@Slf4j
public class RunLevelStore {

    private static final String COLUMNS = """
            record_day, current_run_level, a_speed, e_speed, i_speed, m_speed, r_speed,
            km1_seconds, km3_seconds, km5_seconds, km10_seconds,
            half_marathon_seconds, marathon_seconds
            """;

    private static final String SELECT_RECENT = "SELECT " + COLUMNS
            + " FROM run_level ORDER BY record_day DESC LIMIT :limit";

    private static final String SELECT_ON_OR_BEFORE = "SELECT " + COLUMNS
            + " FROM run_level WHERE record_day <= :day ORDER BY record_day DESC LIMIT 1";

    private static final String SELECT_BETWEEN = "SELECT " + COLUMNS
            + " FROM run_level WHERE record_day >= :from AND record_day <= :to ORDER BY record_day";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 五档配速的字母、通用叫法与对应列，顺序即输出顺序。 */
    private static final Map<String, ZoneMeta> ZONES = zones();

    /** 官方成绩的档位：距离（公里）、距离（米）与对应列。 */
    private static final List<DistanceMeta> DISTANCES = List.of(
            new DistanceMeta(1d, 1000d, "km1_seconds"),
            new DistanceMeta(3d, 3000d, "km3_seconds"),
            new DistanceMeta(5d, 5000d, "km5_seconds"),
            new DistanceMeta(10d, 10000d, "km10_seconds"),
            new DistanceMeta(21.0975d, 21097.5d, "half_marathon_seconds"),
            new DistanceMeta(42.195d, 42195d, "marathon_seconds"));

    private final JdbcClient jdbc;

    public RunLevelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 最近 {@code limit} 天的快照，新的在前；{@code limit <= 0} 时返回空列表。 */
    public List<RunLevel> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 必须用 list() 而不是 stream()：stream() 走 queryForStream，连接要等流关闭才归还，
        // 而 Hikari 池只有 1 条连接（SQLite 单写者），漏一次就把后面的查询全堵死。
        return jdbc.sql(SELECT_RECENT).param("limit", limit).query((rs, rowNum) -> toRunLevel(rs)).list().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 最新一天的快照。表为空时返回 {@link Optional#empty()}。 */
    public Optional<RunLevel> latest() {
        return recent(1).stream().findFirst();
    }

    /** {@code day} 当天或之前最近一天的快照；没有更早的数据时返回空。 */
    public Optional<RunLevel> latestOnOrBefore(LocalDate day) {
        if (day == null) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT_ON_OR_BEFORE)
                .param("day", day.format(DAY_KEY))
                .query((rs, rowNum) -> toRunLevel(rs)).list().stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /** 闭区间内的快照，按日期升序。 */
    public List<RunLevel> between(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        return jdbc.sql(SELECT_BETWEEN)
                .param("from", from.format(DAY_KEY))
                .param("to", to.format(DAY_KEY))
                .query((rs, rowNum) -> toRunLevel(rs)).list().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static RunLevel toRunLevel(java.sql.ResultSet rs) throws java.sql.SQLException {
        String rawDay = rs.getString("record_day");
        LocalDate day = parseDay(rawDay);
        if (day == null) {
            // 日期读不出来的行无法定位到时间轴，宁可丢掉也不要用错日期
            log.debug("跳过 record_day 无法解析的 run_level 行：{}", rawDay);
            return null;
        }
        List<RunLevel.Zone> zones = new ArrayList<>();
        for (ZoneMeta meta : ZONES.values()) {
            Double speed = RunRows.real(rs, meta.column());
            if (speed == null || speed <= 0) {
                continue;
            }
            // 华为存的是 km/h，配速按 3600 / 速度 换算；速度越小配速越慢
            double pace = PaceFormat.round(3600d / speed, 1);
            zones.add(new RunLevel.Zone(meta.code(), meta.name(), speed, pace, PaceFormat.pace(pace)));
        }
        List<RunLevel.OfficialTime> times = new ArrayList<>();
        for (DistanceMeta meta : DISTANCES) {
            Double seconds = RunRows.real(rs, meta.column());
            if (seconds == null || seconds <= 0) {
                continue;
            }
            double pace = PaceFormat.round(seconds / meta.distanceKm(), 1);
            times.add(new RunLevel.OfficialTime(meta.distanceKm(), meta.distanceMeters(), seconds,
                    PaceFormat.clock(seconds), pace, PaceFormat.pace(pace)));
        }
        return new RunLevel(day, RunRows.real(rs, "current_run_level"), List.copyOf(zones), List.copyOf(times));
    }

    private static LocalDate parseDay(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // 华为原文是 yyyyMMdd，长度正好 8；其它形式交给统一的宽松解析
            String value = text.trim();
            if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(value, DAY_KEY);
            }
            return DateParser.parseDate(value);
        } catch (RuntimeException e) {
            log.debug("无法解析 run_level.record_day：{}", text, e);
            return null;
        }
    }

    private static Map<String, ZoneMeta> zones() {
        Map<String, ZoneMeta> map = new LinkedHashMap<>();
        for (ZoneMeta meta : List.of(
                new ZoneMeta("A", "有氧耐力", "a_speed"),
                new ZoneMeta("E", "轻松跑", "e_speed"),
                new ZoneMeta("I", "间歇跑", "i_speed"),
                new ZoneMeta("M", "马拉松配速", "m_speed"),
                new ZoneMeta("R", "重复跑", "r_speed"))) {
            map.put(meta.code(), meta);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private record ZoneMeta(String code, String name, String column) {
    }

    private record DistanceMeta(double distanceKm, double distanceMeters, String column) {
    }
}
