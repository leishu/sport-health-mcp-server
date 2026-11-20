package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.BodyMeasurement;
import org.example.sporthealth.domain.BodyProfile;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 体脂秤数据的只读访问：{@code body_measurement}（一次称重一行）。
 *
 * <p><b>这张表最大的特点是「一台秤、多个人」</b>，不是「一个人多条记录」。
 * 实测 308 条分 7 组（按身高 + 性别）：
 * <ul>
 *   <li>{@code 186/男} 141 条，2025-12-21 起，带全套体成分；</li>
 *   <li>{@code 168/女} 132 条、{@code 171/女} 25 条、{@code 193/男} 3 条；</li>
 *   <li>另有 7 条 {@code gender} 为 NULL（其中 1 条连 {@code height_cm} 都没有）。</li>
 * </ul>
 * 所以任何「体重均值 / 体重变化」都必须先按配置过滤。全表 {@code MIN/MAX(weight_kg)}
 * 得到 55.85~85.35，那不是一个人的体重波动，是四五个人拼起来的极差。
 *
 * <p>另一个坑是「同一天多条」：141 条里有 29 条与前一条相隔不到 1 天
 * （如同一天 05:08 与 05:09 各称一次），所以按天聚合会丢信息、按条数会高估「测了多少天」。
 *
 * <p>{@code measured_at} 是带时区的本地时间文本（{@code 2026-11-09T05:47:01}，
 * 少数带毫秒），按字符串比较即可，右边界用次日零点做开区间。
 */
@Component
@Slf4j
public class BodyCompositionStore {

    /**
     * 按「身高 + 性别」分组。
     *
     * <p>{@code MIN/MAX(weight_kg)} 放在 {@code GROUP BY} 里，取到的才是**每套配置自己**
     * 的体重区间；不带 GROUP BY 的同样两个聚合会把所有人的体重混成一个极差。
     *
     * <p>身高用 {@code ROUND(height_cm)} 分组：库里是 REAL（186.0），四舍五入到整数
     * 才能让「传 186」稳定命中，也不会因为浮点尾数把同一个人拆成两组。
     */
    private static final String SELECT_PROFILES = """
            SELECT ROUND(height_cm) AS height, gender,
                   COUNT(*) AS n,
                   SUM(CASE WHEN has_body_composition = 1 THEN 1 ELSE 0 END) AS comp,
                   MIN(measured_at) AS first_seen, MAX(measured_at) AS last_seen,
                   MIN(weight_kg) AS weight_min, MAX(weight_kg) AS weight_max
            FROM body_measurement
            WHERE measured_at >= :from AND measured_at < :to
            GROUP BY ROUND(height_cm), gender
            ORDER BY n DESC
            """;

    private static final String SELECT_MEASUREMENTS = """
            SELECT measured_at, weight_kg, bmi, body_fat_percent, body_fat_kg, muscle_mass_kg,
                   protein_percent, body_water_percent, bone_salt_kg, visceral_fat_level,
                   waist_hip_ratio, basal_metabolism_kcal, body_age, body_score, heart_rate
            FROM body_measurement
            WHERE measured_at >= :from AND measured_at < :to
              AND gender = :gender
              AND ROUND(height_cm) = :height
            ORDER BY measured_at
            """;

    private static final String SELECT_LATEST = """
            SELECT MAX(measured_at) AS latest FROM body_measurement
            """;

    private static final String SELECT_COVERED = """
            SELECT MIN(measured_at) AS first_seen, MAX(measured_at) AS last_seen
            FROM body_measurement
            """;

    private static final String SELECT_LATEST_FOR_PROFILE = """
            SELECT MAX(measured_at) AS latest FROM body_measurement
            WHERE gender = :gender AND ROUND(height_cm) = :height
            """;

    private final JdbcClient jdbc;

    public BodyCompositionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 区间内按配置分组的汇总，条数从多到少。
     *
     * <p>不在这里按 gender / height 过滤：调用方可能只想传其中一个，过滤放在服务层做，
     * 这样「只传 gender」时还能把该性别下所有身高列出来。
     */
    public List<BodyProfile> profiles(DateRange range) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_PROFILES)
                .param("from", startOf(range.from()))
                .param("to", startOf(range.to().plusDays(1)))
                .query((rs, rowNum) -> toProfile(rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 某套配置在区间内的测量明细，按时间升序。 */
    public List<BodyMeasurement> measurements(DateRange range, int gender, int heightCm) {
        if (range == null) {
            return List.of();
        }
        return jdbc.sql(SELECT_MEASUREMENTS)
                .param("from", startOf(range.from()))
                .param("to", startOf(range.to().plusDays(1)))
                .param("gender", gender)
                .param("height", heightCm)
                .query((rs, rowNum) -> toMeasurement(rs.getString("measured_at"), rs)).list().stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 全库最后一次称重的日期；空表返回 {@code empty}。 */
    public Optional<LocalDate> latestDate() {
        return jdbc.sql(SELECT_LATEST)
                .query((rs, rowNum) -> dayOf(rs.getString("latest")))
                .optional()
                .filter(Objects::nonNull);
    }

    /** 全库首次与最后一次称重的日期；空表返回 {@code empty}。 */
    public Optional<DateRange> coveredRange() {
        return jdbc.sql(SELECT_COVERED)
                .query((rs, rowNum) -> {
                    LocalDate first = dayOf(rs.getString("first_seen"));
                    LocalDate last = dayOf(rs.getString("last_seen"));
                    return first == null || last == null ? null : new DateRange(first, last);
                }).list().stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** 某套配置最后一次称重的日期，用来给默认区间定锚点。 */
    public Optional<LocalDate> latestDateFor(int gender, int heightCm) {
        return jdbc.sql(SELECT_LATEST_FOR_PROFILE)
                .param("gender", gender)
                .param("height", heightCm)
                .query((rs, rowNum) -> dayOf(rs.getString("latest")))
                .optional()
                .filter(Objects::nonNull);
    }

    private static BodyProfile toProfile(java.sql.ResultSet rs) throws SQLException {
        String first = dateOf(rs.getString("first_seen"));
        String last = dateOf(rs.getString("last_seen"));
        if (first == null || last == null) {
            return null;
        }
        Object rawHeight = rs.getObject("height");
        Integer height = rawHeight == null ? null : ((Number) rawHeight).intValue();
        Object rawGender = rs.getObject("gender");
        Integer genderCode = rawGender == null ? null : ((Number) rawGender).intValue();
        String gender = genderCode == null ? "unknown" : genderCode == 1 ? "male" : "female";
        return new BodyProfile(
                gender,
                height,
                rs.getInt("n"),
                rs.getInt("comp"),
                first,
                last,
                PaceFormat.round(rs.getDouble("weight_min"), 2),
                PaceFormat.round(rs.getDouble("weight_max"), 2),
                label(gender, height));
    }

    private static BodyMeasurement toMeasurement(String measuredAt, java.sql.ResultSet rs)
            throws SQLException {
        String date = dateOf(measuredAt);
        if (date == null) {
            return null;
        }
        return new BodyMeasurement(
                date,
                clockOf(measuredAt),
                PaceFormat.round(rs.getDouble("weight_kg"), 2),
                round(rs.getObject("bmi")),
                round(rs.getObject("body_fat_percent")),
                round(rs.getObject("body_fat_kg")),
                round(rs.getObject("muscle_mass_kg")),
                round(rs.getObject("protein_percent")),
                round(rs.getObject("body_water_percent")),
                round(rs.getObject("bone_salt_kg")),
                round(rs.getObject("visceral_fat_level")),
                round(rs.getObject("waist_hip_ratio")),
                round(rs.getObject("basal_metabolism_kcal")),
                (Integer) rs.getObject("body_age"),
                round(rs.getObject("body_score")),
                (Integer) rs.getObject("heart_rate"));
    }

    private static Double round(Object value) {
        return value == null ? null : PaceFormat.round(((Number) value).doubleValue(), 2);
    }

    /** 配置的展示名，给 LLM 与人看都用它。 */
    static String label(String gender, Integer heightCm) {
        String sex = switch (gender) {
            case "male" -> "男";
            case "female" -> "女";
            default -> "性别未知";
        };
        return heightCm == null ? sex + " · 身高未知" : heightCm + "cm · " + sex;
    }

    private static String dateOf(String raw) {
        return raw == null || raw.length() < 10 ? null : raw.substring(0, 10);
    }

    private static String clockOf(String raw) {
        return raw == null || raw.length() < 16 ? null : raw.substring(11, 16);
    }

    private static LocalDate dayOf(String raw) {
        String date = dateOf(raw);
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            log.warn("body_measurement.measured_at 无法解析，忽略：{}", raw);
            return null;
        }
    }

    private static String startOf(LocalDate date) {
        return date.toString();
    }
}
