package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.BodyMeasurement;
import org.example.sporthealth.domain.BodyMetricTrend;
import org.example.sporthealth.domain.BodyMonthSummary;
import org.example.sporthealth.domain.BodyProfile;
import org.example.sporthealth.service.BodyCompositionService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 体成分（体脂秤）的 MCP 工具。
 *
 * <p>这个工具分两种模式，由参数决定：
 * <ul>
 *   <li><b>不给 gender + heightCm</b>：只返回配置汇总 —— 每套配置的条数、日期范围、体重区间，
 *       并明确要求补参数。**绝不自动挑一套去算**，哪怕它条数最多；</li>
 *   <li><b>两个都给</b>：返回这套配置的明细、六项指标的区间变化、按月概览。</li>
 * </ul>
 *
 * <p>为什么必须这样：{@code body_measurement} 是一台秤的多用户数据，308 条分属 7 套配置，
 * 全表体重 55.85~85.35 kg 是四五个人拼出来的。直接 {@code MIN/MAX(weight_kg)} 得到的
 * 「体重区间」没有任何生理意义。
 */
@Component
@Slf4j
public class BodyCompositionTools {

    /** {@code gender} 的规范值。中文与单字母写法在归一化时接受，但只回显这两个。 */
    private static final List<String> GENDER_VALUES = List.of("male", "female");

    /** 身高允许范围（厘米）：超出这个区间基本是单位写错或打错字。 */
    private static final int MIN_HEIGHT_CM = 100;
    private static final int MAX_HEIGHT_CM = 250;

    /**
     * 明细条数上限。一条约 300 字节：240 条约 70 KB。
     *
     * <p>实测单人 141 条（近一年），正常查询远不到这个数；超了说明区间跨太久，
     * 该缩短区间或看 byMonth，而不是把整张表倒出来。
     */
    private static final int MAX_MEASUREMENTS = 240;

    private final BodyCompositionService body;

    public BodyCompositionTools(BodyCompositionService body) {
        this.body = body;
    }

    /**
     * 体成分查询：配置汇总（不传过滤）或单人明细（传 gender + heightCm）。
     */
    @McpTool(name = "get_body_composition",
            description = "查询体脂秤数据（体重、BMI、体脂率、肌肉量、体水分、骨盐量、内脏脂肪等级、"
                    + "腰臀比、基础代谢、体年龄、体成分评分、称重时心率）。"
                    + "**这张表是一台秤的多用户数据，必须先指定是谁**："
                    + "不传 gender 与 heightCm 时只返回 configs（每套配置的条数、日期范围、体重区间）"
                    + "并提示补参数，不会给出任何合并后的体重统计；两个都传才返回明细。"
                    + "明细模式返回 measurements（按时间升序，含同日多次称重）、"
                    + "trends（体重/BMI/体脂率/肌肉量/内脏脂肪/评分的首末值与变化）、"
                    + "byMonth（按月均值，看长期趋势用它比逐条读更快）。"
                    + "用于回答“我最近体重/体脂率怎么样”“这个月瘦了多少”“肌肉量有没有涨”"
                    + "“库里有哪些人的体脂秤记录”。"
                    + "注意：体成分字段只有上秤测了体成分才有，只称体重的记录这些是 null 不是 0；"
                    + "趋势里的 count 是“该指标有值的条数”，会比明细条数少。")
    public Map<String, Object> getBodyComposition(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd；不传时按该配置最后一次称重往前 90 天",
                    required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取该配置最后一次称重那天",
                    required = false) String endDate,
            @McpToolParam(description = "性别：male 或 female（也接受 男/女）。"
                    + "必须与 heightCm 一起给才会进入明细模式", required = false) String gender,
            @McpToolParam(description = "身高（厘米，整数，如 186）。必须与 gender 一起给才会进入明细模式",
                    required = false) Integer heightCm) {
        String sex = normalizeGender(gender);
        if (sex == null && gender != null && !gender.isBlank()) {
            return invalidArgument("gender", gender,
                    "无法识别的性别 \"" + gender + "\"，支持 male / female（或 男 / 女）；不传则不按性别过滤",
                    GENDER_VALUES);
        }
        if (heightCm != null && (heightCm < MIN_HEIGHT_CM || heightCm > MAX_HEIGHT_CM)) {
            return invalidArgument("heightCm", heightCm,
                    "身高 " + heightCm + " cm 超出合理范围（" + MIN_HEIGHT_CM + "~" + MAX_HEIGHT_CM
                            + " cm），请确认单位是厘米", null);
        }
        LocalDate from;
        LocalDate to;
        try {
            from = isPresent(startDate) ? DateParser.parseDate(startDate.trim()) : null;
            to = isPresent(endDate) ? DateParser.parseDate(endDate.trim()) : null;
        } catch (RuntimeException e) {
            String bad = isPresent(startDate) ? startDate : endDate;
            String param = isPresent(startDate) ? "startDate" : "endDate";
            return invalidArgument(param, bad,
                    "无法解析日期 \"" + bad + "\"，请使用 yyyy-MM-dd", null);
        }
        if (from != null && to != null && from.isAfter(to)) {
            return invalidArgument("startDate", startDate,
                    "开始日期 " + from + " 晚于结束日期 " + to + "，区间内必然没有数据", null);
        }

        // 参数不全就走配置汇总：这是刻意设计，不是降级
        if (sex == null || heightCm == null) {
            return configs(startDate, endDate, sex, heightCm);
        }
        return measurements(startDate, endDate, sex, heightCm);
    }

    /** 配置汇总模式：列出区间内每套配置，提示还必须补哪些参数。 */
    private Map<String, Object> configs(String startDate, String endDate, String sex, Integer heightCm) {
        LocalDate anchor = body.latestDate();
        if (anchor == null) {
            return noData("库里没有体脂秤记录。");
        }
        // 配置发现要看完整历史：只开 90 天窗口会漏掉那些很久没上秤的人，
        // 而「漏掉」的后果是调用方永远不知道该传哪个 heightCm。
        DateRange range = isPresent(startDate) || isPresent(endDate)
                ? body.resolveRange(startDate, endDate, anchor)
                : body.coveredRange();
        List<BodyProfile> profiles = body.profiles(range, sex, heightCm);

        if (profiles.isEmpty()) {
            String missing = sex == null ? "gender" : heightCm == null ? "heightCm" : "gender/heightCm 组合";
            return noData("区间 " + range.text() + " 内没有符合 "
                    + (sex == null ? "" : sex + " / ") + (heightCm == null ? "" : heightCm + "cm ")
                    + "的记录（" + missing + " 对不上库里的任何一套配置）。", range);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("mode", "configs");
        result.put("range", range.text());
        result.put("configs", profiles);
        result.put("profileCount", profiles.size());
        result.put("measurementCount", profiles.stream().mapToInt(BodyProfile::measurements).sum());
        result.put("notes", body.profileNotes(profiles, range, sex, heightCm));
        return result;
    }

    /** 明细模式：gender 与 heightCm 都给齐后才走这里。 */
    private Map<String, Object> measurements(String startDate, String endDate, String sex, Integer heightCm) {
        LocalDate anchor = body.latestDateFor(sex, heightCm);
        if (anchor == null) {
            return noData("库里没有 " + sex + " / " + heightCm + "cm 这套配置的记录。");
        }
        DateRange range = body.resolveRange(startDate, endDate, anchor);

        // 取区间内全部配置，既用来定位自己，也用来在 notes 里说明排除了谁
        List<BodyProfile> all = body.profiles(range, null, null);
        BodyProfile mine = all.stream()
                .filter(profile -> sex.equals(profile.gender()) && heightCm.equals(profile.heightCm()))
                .findFirst().orElse(null);
        if (mine == null) {
            return noData("区间 " + range.text() + " 内没有 " + sex + " / " + heightCm
                    + "cm 这套配置的称重记录。", range);
        }

        List<BodyMeasurement> items = body.measurements(mine, range);
        if (items.isEmpty()) {
            return noData("区间 " + range.text() + " 内没有 " + mine.label() + " 的称重记录。", range);
        }
        if (items.size() > MAX_MEASUREMENTS) {
            return invalidArgument("startDate", startDate,
                    "区间 " + range.text() + " 内 " + mine.label() + " 有 " + items.size()
                            + " 条测量，超过一次返回上限 " + MAX_MEASUREMENTS
                            + " 条，请缩短区间（或先看 byMonth）", null);
        }

        Map<String, BodyMetricTrend> trends = body.trends(items);
        List<BodyMonthSummary> months = body.byMonth(items);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("mode", "measurements");
        result.put("range", range.text());
        result.put("profile", mine);
        result.put("count", items.size());
        result.put("measurements", items);
        result.put("trends", trends);
        result.put("byMonth", months);
        result.put("notes", body.notes(mine, range, items, all));
        return result;
    }

    /**
     * 性别归一化：不传返回 {@code null}（= 不过滤），认不出也返回 {@code null}，
     * 由调用方用「是否真的传了」区分「不过滤」与「传错了」。
     */
    private static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        return switch (gender.trim().toLowerCase(Locale.ROOT)) {
            case "male", "m", "1", "男" -> "male";
            case "female", "f", "0", "女" -> "female";
            default -> null;
        };
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Object> invalidArgument(String param, Object value, String message,
                                                List<String> allowedValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INVALID_ARGUMENT");
        result.put("param", param);
        result.put("value", value);
        result.put("message", message);
        if (allowedValues != null && !allowedValues.isEmpty()) {
            result.put("allowedValues", allowedValues);
        }
        return result;
    }

    private Map<String, Object> noData(String message) {
        return noData(message, null);
    }

    /**
     * 没数据时把库里真实可用的配置一并返回：只说「没有」，
     * 调用方还得再猜一次参数。
     */
    private Map<String, Object> noData(String message, DateRange range) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", message);
        if (range != null) {
            result.put("range", range.text());
        }
        DateRange covered = body.coveredRange();
        if (covered != null) {
            result.put("dataRange", covered.text());
            result.put("availableConfigs", body.profiles(covered, null, null));
            result.put("hint", "从 availableConfigs 里选一套，把 gender 与 heightCm 一起传进来。");
        }
        return result;
    }
}
