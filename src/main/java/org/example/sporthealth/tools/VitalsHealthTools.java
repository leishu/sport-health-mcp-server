package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.HeartRateStat;
import org.example.sporthealth.domain.VitalsAvailability;
import org.example.sporthealth.domain.VitalsCoverage;
import org.example.sporthealth.domain.VitalsMetricStat;
import org.example.sporthealth.domain.VitalsPeriod;
import org.example.sporthealth.service.VitalsAnalyticsService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 体征与身体成分的 MCP 工具。
 *
 * <p>单独一个类：体征只来自 {@code daily_vitals} 一张日快照表，和跑步分析、睡眠分期都没有依赖，
 * 塞进三千多行的 {@code SportHealthTools} 只会让那个类更难读。
 *
 * <p>返回体约定与其它工具一致：{@code status} 区分 {@code OK} / {@code NO_DATA} /
 * {@code INVALID_ARGUMENT}；参数「不传 = 默认值，传了但值非法 = 报错」，不静默替换。
 */
@Component
@Slf4j
public class VitalsHealthTools {

    /** {@code groupBy} 的允许值，用于 {@code INVALID_ARGUMENT} 的提示。 */
    private static final List<String> GROUP_VALUES = List.of("day", "week", "month");

    /**
     * 逐日输出的区间上限。
     *
     * <p>实测一行约 160 字节：92 天约 15 KB。再长就该用 week / month ——
     * 逐日明细挤爆上下文之后，结论反而没地方放了。
     */
    private static final int MAX_DAILY_SPAN = 92;

    /** 周粒度的区间上限（约 3 年）。一个周期约 200 字节，156 周约 31 KB。 */
    private static final int MAX_WEEKLY_SPAN = 1096;

    /** 月粒度的区间上限（约 5 年）。数据本身才 468 天，这个上限只是兜底。 */
    private static final int MAX_MONTHLY_SPAN = 1826;

    private final VitalsAnalyticsService vitals;

    public VitalsHealthTools(VitalsAnalyticsService vitals) {
        this.vitals = vitals;
    }

    /**
     * 体征汇总：逐日静息心率 / HRV / 压力 / 最大摄氧量 + 区间统计 + 变化方向。
     *
     * <p>三层输出：
     * <ul>
     *   <li>{@code coverage} 与 {@code availability}：多少天真有这类数据、每个指标在全库的可用范围；</li>
     *   <li>{@code days}（{@code groupBy=day}）或 {@code periods}（{@code week/month}）；</li>
     *   <li>{@code metrics} / {@code heartRate}：区间均值、极值、最新值、28 天基线、变化方向。</li>
     * </ul>
     */
    @McpTool(name = "get_daily_vitals",
            description = "按日期区间查询每日体征：静息心率、HRV（RMSSD）、压力（均值与峰值）、"
                    + "最大摄氧量，以及全天自动心率的均值/最低/最高；同时给出区间平均、极值、最新值、"
                    + "相对 28 天基线的偏离，和区间内的变化方向（improving / worsening / flat）。"
                    + "groupBy=day（默认）逐日返回，区间最多 92 天；week / month 按周期聚合，"
                    + "可覆盖 3~5 年。四类指标的可用起点不同：静息心率从 2025-11-08、HRV 与压力从 "
                    + "2026-06-12 才有，最大摄氧量只有 104 天且 2025-09 之后断档 14 个月——"
                    + "某段区间里某指标全空时对应字段是 null（缺数据，不是 0），"
                    + "先看 coverage 与 availability 再判断。metrics 里 direction 是数值涨跌、"
                    + "assessment 才是好坏（静息心率下降是 improving，HRV 下降是 worsening）。"
                    + "用于回答“最近静息心率怎么样”“HRV 是不是变差了”“压力大不大”"
                    + "“最大摄氧量涨了吗”“恢复得好不好”。")
    public Map<String, Object> getDailyVitals(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最新一天往前 30 天",
                    required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "分组粒度：day（默认，逐日）、week（周一到周日）、month", required = false) String groupBy) {
        Map<String, Object> invalid = validate(startDate, endDate, groupBy);
        if (invalid != null) {
            return invalid;
        }
        String mode = normalizeGroup(groupBy);
        DateRange range = vitals.resolveRange(startDate, endDate);

        int limit = switch (mode) {
            case "week" -> MAX_WEEKLY_SPAN;
            case "month" -> MAX_MONTHLY_SPAN;
            default -> MAX_DAILY_SPAN;
        };
        if (range.dayCount() > limit) {
            String hint = "day".equals(mode)
                    ? "；要更长区间请改用 groupBy=week 或 month"
                    : "；已经是 " + mode + " 粒度了，请缩短区间";
            return invalidArgument("endDate", endDate,
                    "groupBy=" + mode + " 时区间最多 " + limit + " 天，当前 " + range.text()
                            + " 共 " + range.dayCount() + " 天" + hint, null);
        }

        List<DailyVitals> days = vitals.days(range);
        if (days.isEmpty()) {
            return noData(range);
        }

        VitalsCoverage coverage = vitals.coverage(range, days);
        List<VitalsAvailability> availability = vitals.availability();
        Map<String, VitalsMetricStat> metrics = vitals.metrics(range, days);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("groupBy", mode);
        result.put("coverage", coverage);
        result.put("availability", availability);
        if ("day".equals(mode)) {
            result.put("days", days);
        } else {
            List<VitalsPeriod> periods = vitals.periods(days, range, mode);
            result.put("periods", periods);
            result.put("periodCount", periods.size());
        }
        result.put("metrics", metrics);
        HeartRateStat heartRate = vitals.heartRate(days);
        if (heartRate != null) {
            result.put("heartRate", heartRate);
        }
        result.put("notes", vitals.notes(range, coverage, metrics, availability));
        return result;
    }

    /**
     * 参数校验：日期格式与先后顺序、{@code groupBy} 枚举。
     *
     * <p>日期解析失败在这里转成 {@code INVALID_ARGUMENT} 而不是抛异常：默认区间的另一端来自
     * 数据库，用户打错日期时抛异常只会让调用方看到一个没有上下文的错误。
     *
     * @return {@code null} 表示全部合法
     */
    private Map<String, Object> validate(String startDate, String endDate, String groupBy) {
        if (normalizeGroup(groupBy) == null) {
            return invalidArgument("groupBy", groupBy,
                    "无法识别的分组粒度 \"" + groupBy + "\"，支持 day / week / month；不传按 day",
                    GROUP_VALUES);
        }
        LocalDate from = null;
        LocalDate to = null;
        if (isPresent(startDate)) {
            from = parseDateArg(startDate);
            if (from == null) {
                return invalidArgument("startDate", startDate,
                        "无法解析日期 \"" + startDate + "\"，请使用 yyyy-MM-dd", null);
            }
        }
        if (isPresent(endDate)) {
            to = parseDateArg(endDate);
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

    /**
     * 分组粒度归一化：不传按 {@code day}；给了但认不出来返回 {@code null}，
     * 由调用方转成 {@code INVALID_ARGUMENT} —— 静默按 day 返回会让「按周看」的请求拿到逐日数据。
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

    private static LocalDate parseDateArg(String value) {
        try {
            return DateParser.parseDate(value.trim());
        } catch (RuntimeException e) {
            log.debug("体征工具的日期解析失败：{}", value);
            return null;
        }
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

    private Map<String, Object> noData(DateRange range) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "区间 " + range.text() + " 内没有体征记录。");
        result.put("from", range.from());
        result.put("to", range.to());
        vitals.availability().stream()
                .filter(a -> "restingHeartRate".equals(a.metric()))
                .findFirst()
                .ifPresent(a -> result.put("coveredRange", a.from() + " ~ " + a.to()));
        return result;
    }
}
