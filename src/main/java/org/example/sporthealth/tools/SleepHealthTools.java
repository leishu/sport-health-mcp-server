package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.SleepCoverage;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepPeriod;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.service.SleepAnalyticsService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 睡眠相关的 MCP 工具。
 *
 * <p>单独一个类而不是塞进 {@code SportHealthTools}：睡眠完全不依赖 TCX 与跑步分析，
 * 数据只来自 SQLite 里的睡眠分期与睡眠报告，混在一起会让本来就三千多行的工具类更难读。
 *
 * <p>返回体约定与其它工具一致：{@code status} 区分 {@code OK} / {@code NO_DATA} /
 * {@code INVALID_ARGUMENT}；参数「不传 = 默认值，传了但值非法 = 报错」，不静默替换。
 */
@Component
@Slf4j
public class SleepHealthTools {

    /** {@code groupBy} 的允许值，用于 {@code INVALID_ARGUMENT} 的提示。 */
    private static final List<String> GROUP_VALUES = List.of("day", "week", "month");

    private final SleepAnalyticsService sleep;

    public SleepHealthTools(SleepAnalyticsService sleep) {
        this.sleep = sleep;
    }

    /**
     * 睡眠汇总：逐夜明细 + 区间平均 + 数据覆盖情况。
     *
     * <p>输出三个块：
     * <ul>
     *   <li>{@code coverage}：{@code sessionDays} / {@code reportDays} / {@code spo2Nights} 等计数，
     *       说明「多少夜晚真有这类数据」；</li>
     *   <li>{@code nights}（{@code groupBy=day}）或 {@code periods}（{@code week/month}）；</li>
     *   <li>{@code summary}：区间平均，每个平均值都带自己的分母计数。</li>
     * </ul>
     */
    @McpTool(name = "get_sleep_summary",
            description = "按日期区间查询睡眠：每个夜晚的入睡/起床时刻、卧床与睡着时长、"
                    + "深睡/浅睡/快速眼动时长、夜间清醒时长与醒来次数，以及睡眠评分、效率、HRV、血氧，"
                    + "外加整个区间的平均值。groupBy=day（默认）逐夜返回 nights；week/month 按周期聚合返回 periods。"
                    + "数据有两套来源：睡眠分期覆盖全部历史，官方睡眠报告只有 2026-06-13 之后才有，"
                    + "所以更早的夜晚 score/efficiency/hrv/spo2 是 null——那是缺数据，不是 0，"
                    + "先看 coverage.sessionDays / coverage.reportDays / coverage.spo2Nights 再判断。"
                    + "wakeCount 由分期里的连续清醒段推算（≥2 分钟才算一次，入睡前与起床前那段不算），"
                    + "华为报告的 wakeCount 字段恒为 0 不可用。午睡不并入 asleepMinutes，单独记在 napMinutes。"
                    + "用于回答“我最近睡得怎么样”“这个月平均睡多久”“睡眠评分多少”“深睡够不够”。")
    public Map<String, Object> getSleepSummary(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取睡眠数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取睡眠数据最新一天", required = false) String endDate,
            @McpToolParam(description = "分组粒度：day（默认，逐夜）、week、month", required = false) String groupBy) {
        Map<String, Object> invalid = validate(startDate, endDate, groupBy);
        if (invalid != null) {
            return invalid;
        }
        String mode = normalizeGroup(groupBy);
        DateRange range = sleep.resolveRange(startDate, endDate);
        List<SleepNight> nights = sleep.nights(range);
        if (nights.isEmpty()) {
            return noData(range);
        }

        SleepCoverage coverage = sleep.coverage(nights);
        SleepSummary summary = sleep.summarize(nights);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("groupBy", mode);
        result.put("coverage", coverage);
        if ("day".equals(mode)) {
            result.put("nights", nights);
        } else {
            List<SleepPeriod> periods = sleep.periods(nights, mode);
            result.put("periods", periods);
            result.put("periodCount", periods.size());
        }
        result.put("summary", summary);
        return result;
    }

    /**
     * 参数校验：日期格式与先后顺序、{@code groupBy} 枚举。
     *
     * <p>日期解析失败在别处是抛异常，这里改成 {@code INVALID_ARGUMENT}：
     * 睡眠的默认区间来自睡眠表自身，用户打错日期时如果抛异常，调用方只能看到一个没有上下文的错误。
     *
     * @return {@code null} 表示全部合法
     */
    private Map<String, Object> validate(String startDate, String endDate, String groupBy) {
        if (normalizeGroup(groupBy) == null) {
            return invalidArgument("groupBy", groupBy,
                    "无法识别的分组粒度 \"" + groupBy + "\"，支持 day / week / month；不传按 day", GROUP_VALUES);
        }
        LocalDate from = null;
        LocalDate to = null;
        if (isPresent(startDate)) {
            from = parseDateArg("startDate", startDate);
            if (from == null) {
                return invalidArgument("startDate", startDate,
                        "无法解析日期 \"" + startDate + "\"，请使用 yyyy-MM-dd", null);
            }
        }
        if (isPresent(endDate)) {
            to = parseDateArg("endDate", endDate);
            if (to == null) {
                return invalidArgument("endDate", endDate,
                        "无法解析日期 \"" + endDate + "\"，请使用 yyyy-MM-dd", null);
            }
        }
        if (from != null && to != null && from.isAfter(to)) {
            return invalidArgument("startDate", startDate,
                    "开始日期 " + from + " 晚于结束日期 " + to + "，区间内必然没有数据", null);
        }
        return null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static LocalDate parseDateArg(String param, String value) {
        if (!isPresent(value)) {
            return null;
        }
        try {
            return DateParser.parseDate(value.trim());
        } catch (RuntimeException e) {
            log.debug("睡眠工具的 {} 解析失败：{}", param, value);
            return null;
        }
    }

    /**
     * 分组粒度归一化：不传按 {@code day}；给了但认不出来返回 {@code null}，
     * 由调用方转成 {@code INVALID_ARGUMENT}——静默按 day 返回会让「按周看」的请求拿到逐夜数据。
     */
    private static String normalizeGroup(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "day";
        }
        String key = groupBy.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "day", "week", "month" -> key;
            default -> null;
        };
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

    private Map<String, Object> noData(DateRange range) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "区间 " + range.text() + " 内没有睡眠记录。");
        result.put("from", range.from());
        result.put("to", range.to());
        sleep.coveredRange().ifPresent(covered -> result.put("coveredRange", covered.text()));
        return result;
    }
}
