package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.ActivityCoverage;
import org.example.sporthealth.domain.ActivityGoals;
import org.example.sporthealth.domain.ActivityPeriod;
import org.example.sporthealth.domain.ActivitySummary;
import org.example.sporthealth.domain.ActivityTrend;
import org.example.sporthealth.domain.DailyActivityDay;
import org.example.sporthealth.service.ActivityMinuteService;
import org.example.sporthealth.service.DailyActivityService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 活动量的 MCP 工具，两个视角：
 * <ul>
 *   <li>{@code get_daily_activity}：多天/周/月的活动量（{@code daily_activity}，一天一行）；</li>
 *   <li>{@code get_activity_minutes}：某一天的逐分钟明细（{@code activity_minute}，一分钟一行）。</li>
 * </ul>
 *
 * <p>单独一个类：活动量只来自这两张表，和跑步分析、睡眠、体征都不共享口径 ——
 * 它回答的是「这周每天走了多少、够不够」和「这一天每分钟在做什么」，而跑步工具回答的是
 * 「这次跑得怎么样」。两者的「距离」甚至不能相加：这里是全天走路 + 跑步的累计，
 * 跑步工具里的是单次轨迹。
 *
 * <p>返回体约定与其它工具一致：{@code status} 区分 {@code OK} / {@code NO_DATA} /
 * {@code INVALID_ARGUMENT}；参数「不传 = 默认值，传了但值非法 = 报错」，不静默替换。
 */
@Component
@Slf4j
public class ActivityHealthTools {

    /** {@code groupBy} 的允许值，用于 {@code INVALID_ARGUMENT} 的提示。 */
    private static final List<String> GROUP_VALUES = List.of("day", "week", "month");

    /** 逐日输出的区间上限。一行约 200 字节，92 天约 18 KB。 */
    private static final int MAX_DAILY_SPAN = 92;

    /** 周粒度的区间上限（约 3 年），一个周期约 300 字节。 */
    private static final int MAX_WEEKLY_SPAN = 1096;

    /** 月粒度的区间上限（约 5 年），数据本身才 368 天，这个上限只是兜底。 */
    private static final int MAX_MONTHLY_SPAN = 1826;

    private final DailyActivityService activity;
    private final ActivityMinuteService minutes;

    public ActivityHealthTools(DailyActivityService activity, ActivityMinuteService minutes) {
        this.activity = activity;
        this.minutes = minutes;
    }

    /**
     * 日常活动量：逐日步数 / 距离 / 活动热量 / 中高强度分钟 / 站立次数 + 区间概览 + 达标判定。
     *
     * <p>三层输出：
     * <ul>
     *   <li>{@code coverage}：多少天真有记录、多少天没记录、多少天记录过少（可能没戴表）；</li>
     *   <li>{@code days}（{@code groupBy=day}）或 {@code periods}（{@code week/month}）；</li>
     *   <li>{@code summary} / {@code goals} / {@code trends}：日均与中位数、三个日目标与 WHO 周线
     *       的达标情况、最近 7 天相对前 7 天的变化。</li>
     * </ul>
     */
    @McpTool(name = "get_daily_activity",
            description = "按日期区间查询日常活动量：每天走了多少步（含走路 / 跑步拆分）、走了多远、"
                    + "活动热量、中高强度分钟数、站立次数（站着起身的事件数，不是分钟数），"
                    + "并给出区间日均与中位数、最高/最低的一天、三个日目标的达成天数"
                    + "（步数 ≥8000、中高强度 ≥30 分钟、站立 ≥12 次）、最近 7 天的中高强度合计"
                    + "是否达到 WHO 每周 150 分钟，以及最近 7 天相对前 7 天的变化方向。"
                    + "groupBy=day（默认）逐日返回，区间最多 92 天；week / month 按周期聚合，"
                    + "可覆盖 3~5 年。coverage 里的 missingDays / sparseDays 说明哪些天没数据、"
                    + "哪些天表没戴够 —— 日均只按有记录的天算，别拿去代表整段区间。"
                    + "用于回答“最近每天走了多少步”“这周活动量够不够”“是不是坐得太久了”"
                    + "“上周比这周走得多吗”。注意步数会把同一分钟的走路 + 跑步各算一次，"
                    + "比华为 App 自报高约 0.8%；单次跑步的配速/心率用 get_run_detail 一类工具。")
    public Map<String, Object> getDailyActivity(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最新一天往前 30 天",
                    required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "分组粒度：day（默认，逐日）、week（周一到周日）、month", required = false) String groupBy) {
        Map<String, Object> invalid = validate(startDate, endDate, groupBy);
        if (invalid != null) {
            return invalid;
        }
        String mode = normalizeGroup(groupBy);
        DateRange range = activity.resolveRange(startDate, endDate);

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

        List<DailyActivityDay> days = activity.days(range);
        if (days.isEmpty()) {
            return noData(range);
        }

        ActivityCoverage coverage = activity.coverage(range, days);
        ActivitySummary summary = activity.summary(days);
        ActivityGoals goals = activity.goals(days);
        Map<String, ActivityTrend> trends = activity.trends(days);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("groupBy", mode);
        result.put("coverage", coverage);
        result.put("summary", summary);
        result.put("goals", goals);
        if ("day".equals(mode)) {
            result.put("days", days);
        } else {
            List<ActivityPeriod> periods = activity.periods(days, range, mode);
            result.put("periods", periods);
            result.put("periodCount", periods.size());
        }
        result.put("trends", trends);
        result.put("notes", activity.notes(range, coverage, summary, goals, trends));
        return result;
    }

    /**
     * 某一天的分钟级明细：每一分钟在做什么 + 连续成了哪几段。
     *
     * <p>和 {@code get_daily_activity} 的分工：那边是「这周每天多少步、够不够」的多天视角，
     * 这边是「这一天 06:00 到 07:00 在干什么」的单天视角。日合计同源，两边对得上。
     */
    @McpTool(name = "get_activity_minutes",
            description = "查询某一天的分钟级活动明细：每一分钟的运动类型（走路 / 跑步 / 站立事件）、"
                    + "这一分钟的步数、距离、活动热量、是否中高强度、来源设备。默认只返回活动分钟"
                    + "（不含站立事件），type 可切换 activity（默认）/ all / walk / run / stand。"
                    + "同时给出三样更好读的东西：types（各类型多少分钟、步数、距离、热量）、"
                    + "blocks（同类型且前后相差 ≤2 分钟拼成一段，如「06:02~07:04 跑步 62 分钟 11 210 步」）、"
                    + "summary（当天合计，与 get_daily_activity 同源）。同一分钟被两台设备各记一条时"
                    + "只保留步数大的那条，summary.rawRows 与 summary.records 的差就是合并掉的条数。"
                    + "用于回答“某天几点开始跑的、跑了多久”“这一天每分钟走了多少步”"
                    + "“这天的走路和跑步分别多少分钟”“为什么这天步数这么多”。"
                    + "要区间/周月的活动量用 get_daily_activity；单次跑步的配速与心率用 get_run_detail。"
                    + "注意站立事件是整点触发的标记（0 步、duration=0），不是「站了几分钟」。")
    public Map<String, Object> getActivityMinutes(
            @McpToolParam(description = "日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String date,
            @McpToolParam(description = "类型过滤：activity（默认，走路 + 跑步 + 其它活动分钟，不含站立事件）、"
                    + "all（全部含站立事件）、walk、run、stand", required = false) String type) {
        String mode = isPresent(type) ? type.trim().toLowerCase(Locale.ROOT) : "activity";
        if (!ActivityMinuteService.FILTERS.contains(mode)) {
            return invalidArgument("type", type,
                    "无法识别的类型 \"" + type + "\"，支持 activity / all / walk / run / stand；不传按 activity",
                    ActivityMinuteService.FILTERS);
        }
        LocalDate day;
        if (isPresent(date)) {
            LocalDate parsed = parseDateArg(date);
            if (parsed == null) {
                return invalidArgument("date", date,
                        "无法解析日期 \"" + date + "\"，请使用 yyyy-MM-dd", null);
            }
            day = parsed;
        } else {
            day = minutes.defaultDate();
        }

        Optional<DateRange> covered = minutes.coveredRange();
        if (covered.isPresent() && !covered.get().contains(day)) {
            return noData(day, covered.get());
        }
        ActivityMinuteService.DayMinutes data = minutes.day(day, mode);
        if (data.summary().records() == 0) {
            return noData(day, covered.orElse(null));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("date", data.summary().date().toString());
        result.put("weekday", data.summary().weekday());
        result.put("filter", mode);
        result.put("summary", data.summary());
        result.put("types", data.types());
        result.put("blocks", data.blocks());
        result.put("blockCount", data.blocks().size());
        result.put("minutes", data.minutes());
        result.put("minuteCount", data.minutes().size());
        result.put("notes", data.notes());
        return result;
    }

    /**
     * 参数校验：日期格式与先后顺序、{@code groupBy} 枚举。
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
            log.debug("活动量工具的日期解析失败：{}", value);
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

    /** 区间内没有记录时，顺带告诉调用方全库实际覆盖到哪一天。 */
    private Map<String, Object> noData(DateRange range) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "区间 " + range.text() + " 内没有活动记录。");
        result.put("from", range.from().toString());
        result.put("to", range.to().toString());
        activity.coveredRange().ifPresent(covered ->
                result.put("coveredRange", covered.from() + " ~ " + covered.to()));
        return result;
    }

    /** 单日查询没有记录时，同样把全库覆盖范围带回去，免得调用方以为是自己日期写错了。 */
    private Map<String, Object> noData(LocalDate day, DateRange covered) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "日期 " + day + " 没有分钟级活动记录。");
        result.put("date", day.toString());
        if (covered != null) {
            result.put("coveredRange", covered.from() + " ~ " + covered.to());
        }
        return result;
    }
}
