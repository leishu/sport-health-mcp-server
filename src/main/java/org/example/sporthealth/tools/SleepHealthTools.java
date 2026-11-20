package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.SleepCoverage;
import org.example.sporthealth.domain.SleepDetailNight;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepPeriod;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.domain.SleepTrendMetric;
import org.example.sporthealth.domain.SleepTrendReport;
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

    /**
     * 详情一次最多返回多少夜。
     *
     * <p>实测一夜带时间线约 3.3 KB、不带约 1.7 KB；14 夜就是 46 KB 上下，
     * 再往上单次返回就会挤占对话上下文，那时该用 {@code get_sleep_summary} 先看趋势。
     */
    private static final int MAX_DETAIL_NIGHTS = 14;

    /** 趋势的分组粒度：{@code day} 被刻意排除，那是 {@code get_sleep_summary} 的活。 */
    private static final List<String> TREND_GROUPS = List.of("week", "month");

    /** 趋势主指标的允许值，直接从枚举取，避免两处维护。 */
    private static final List<String> TREND_METRIC_VALUES =
            java.util.Arrays.stream(SleepTrendMetric.values()).map(SleepTrendMetric::key).toList();

    /**
     * 趋势一次最多返回多少个周期。
     *
     * <p>实测一个点（values + deltas 共 14 个数值）约 0.6 KB：周粒度 78 点约 46 KB、
     * 月粒度 36 点约 21 KB。再长就该缩短区间——返回体先于结论挤爆上下文没有意义。
     * 两个粒度分开定上限，是因为同样 40 KB 能装 6 年月数据、却只装得下 1.5 年周数据。
     */
    private static final int MAX_WEEK_POINTS = 78;
    private static final int MAX_MONTH_POINTS = 36;

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
     * 睡眠详情：一夜（或几夜）的时间线、醒来时刻、睡眠碎片与报告指标。
     *
     * <p>与 {@code get_sleep_summary} 的分工：summary 回答「最近睡得怎么样」，
     * 只给每夜一行；detail 回答「这一夜到底怎么睡的」，把逐分钟的合并分期、
     * 设备认定的碎片块、报告里的心率呼吸都摊开。两者共用同一套基础字段，
     * 同一件事不会出现两个数字。
     */
    @McpTool(name = "get_sleep_detail",
            description = "按夜晚查询睡眠详情：逐段睡眠时间线（深睡/浅睡/快速眼动/清醒，相邻同阶段已合并）、"
                    + "每一次醒来的起止时刻、睡眠碎片（设备认定的连续睡眠块与块间间隙）、各阶段时长与占比，"
                    + "以及报告里的评分/效率/HRV/血氧/心率/呼吸率，外加入睡前的白天午睡。"
                    + "参数二选一：传 date 看某一夜（sleep_date 是醒来那天）；传 startDate/endDate 看区间，最多 14 夜；"
                    + "都不传则取最新一夜。区间更大时改用 get_sleep_summary。"
                    + "三份数据覆盖不同：分期覆盖全部历史，官方报告只有 2026-06-13 之后，"
                    + "睡眠碎片只有 2026-06-12 之后——缺失时对应块是 null 或 available=false，不是 0，"
                    + "先看 coverage.stageNights / reportNights / fragmentNights。"
                    + "wakeEpisodes 与 wakeCount 是分期口径（连续清醒 ≥2 分钟才算一次），"
                    + "fragments.gaps 是设备口径的间隙，两者可能差 1~2 分钟，属口径差异。"
                    + "用于回答“我昨晚几点醒的”“深睡集中在哪个时段”“睡得多碎”“这段睡眠的一次完整周期长什么样”。")
    public Map<String, Object> getSleepDetail(
            @McpToolParam(description = "单夜日期，格式 yyyy-MM-dd，指醒来那天（如 2026-11-10 表示 11-09 夜里那一觉）",
                    required = false) String date,
            @McpToolParam(description = "区间开始日期 yyyy-MM-dd，与 endDate 搭配；不能与 date 同时使用",
                    required = false) String startDate,
            @McpToolParam(description = "区间结束日期 yyyy-MM-dd，区间最多 14 夜", required = false) String endDate,
            @McpToolParam(description = "是否返回逐段的时间线，默认 true；false 时只给各阶段合计与段数",
                    required = false) Boolean includeTimeline) {
        Map<String, Object> invalid = validateDetail(date, startDate, endDate);
        if (invalid != null) {
            return invalid;
        }
        DateRange range = detailRange(date, startDate, endDate);
        long nights = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
        if (nights > MAX_DETAIL_NIGHTS) {
            return invalidArgument("endDate", endDate,
                    "详情最多支持 " + MAX_DETAIL_NIGHTS + " 个夜晚，当前区间 " + range.text()
                            + " 共 " + nights + " 夜；更长区间请用 get_sleep_summary，或传 date 单夜查看",
                    null);
        }

        boolean timeline = includeTimeline == null || includeTimeline;
        List<SleepDetailNight> details = sleep.details(range, timeline);
        if (details.isEmpty()) {
            return noData(range);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("nights", details);
        result.put("nightCount", details.size());
        result.put("coverage", sleep.detailCoverage(details));
        result.put("notes", detailNotes(details, timeline));
        return result;
    }

    /**
     * 详情的参数校验。{@code date} 与区间参数互斥：同时给时按谁都不对，直接报错而不是猜。
     */
    private Map<String, Object> validateDetail(String date, String startDate, String endDate) {
        if (isPresent(date) && (isPresent(startDate) || isPresent(endDate))) {
            return invalidArgument("date", date,
                    "date 与 startDate/endDate 只能二选一：单夜用 date，区间用 startDate + endDate", null);
        }
        if (isPresent(date) && parseDateArg("date", date) == null) {
            return invalidArgument("date", date,
                    "无法解析日期 \"" + date + "\"，请使用 yyyy-MM-dd（sleep_date 是醒来那天）", null);
        }
        return validate(startDate, endDate, null);
    }

    /** 详情的时间区间：{@code date} 单夜；两端都不传时取最新一夜（不是全历史）。 */
    private DateRange detailRange(String date, String startDate, String endDate) {
        if (isPresent(date)) {
            LocalDate day = DateParser.parseDate(date.trim());
            return new DateRange(day, day);
        }
        if (!isPresent(startDate) && !isPresent(endDate)) {
            Optional<DateRange> covered = sleep.coveredRange();
            if (covered.isEmpty()) {
                return new DateRange(LocalDate.now(), LocalDate.now());
            }
            LocalDate last = covered.get().to();
            return new DateRange(last, last);
        }
        return sleep.resolveRange(startDate, endDate);
    }

    /** 给调用方看的读法提示：只在真的出现这类数据时才说，不制造噪音。 */
    private static List<String> detailNotes(List<SleepDetailNight> details, boolean timeline) {
        List<String> notes = new ArrayList<>(2);
        if (!timeline) {
            notes.add("includeTimeline=false，timeline 为空数组，但 timelineSegments 仍是真实段数。");
        }
        boolean fragments = details.stream().anyMatch(n -> n.fragments() != null && n.fragments().available());
        if (fragments) {
            notes.add("fragments.items 是设备认定的连续睡眠块，fragments.gaps 是块之间的间隙；"
                    + "它与 wakeEpisodes（分期口径，连续清醒 ≥2 分钟才算一次）可能差 1~2 分钟，两者都给出、不取平均。");
        }
        if (details.stream().anyMatch(n -> n.timelineTruncated())) {
            notes.add("有夜晚的分期段数超过上限，timeline 被截断，请用 timelineSegments 判断真实段数。");
        }
        return notes;
    }

    /**
     * 睡眠趋势：按周 / 月把区间切成连续周期，逐周期给指标并给出主指标的趋势结论。
     *
     * <p>与 {@code get_sleep_summary} 的分工：summary 回答「这几周各睡了多久」，
     * 每个周期还可以带逐夜明细；trend 回答「是不是在变好」，只输出聚合点，
     * 所以能覆盖更长区间（周粒度最多 {@value #MAX_WEEK_POINTS} 个周期，月粒度 {@value #MAX_MONTH_POINTS} 个）。
     *
     * <p>{@code groupBy=day} 在这里没有意义（那就是 summary 的逐夜输出），
     * 传了会被拒绝而不是静默换成 week——否则「按天看趋势」的请求会拿到周数据。
     */
    @McpTool(name = "get_sleep_trend",
            description = "睡眠趋势：按周（默认）或按月把区间切成连续的周期，每个周期给出平均睡着时长、评分、睡眠效率、"
                    + "HRV、醒来次数、深睡占比、入睡潜伏期，以及与上一个周期相比的变化量；"
                    + "再对指定指标给出趋势结论：首末变化、每周期线性斜率、方向、最好与最差的周期。"
                    + "与 get_sleep_summary 的分工：summary 看某个周期内部（可带逐夜明细），"
                    + "trend 看一串周期连起来的变化，只输出聚合点、可覆盖更长区间。"
                    + "groupBy 只支持 week 与 month，按天请用 get_sleep_summary；"
                    + "metric 可选 asleep（默认，睡着时长）/ score / efficiency / hrv / wakeCount / deep / latency。"
                    + "trend.direction 是数值方向（up/down/flat），trend.assessment 是价值方向"
                    + "（improving/worsening/flat）：醒来次数下降是 improving，不要只看 up/down。"
                    + "趋势拟合只用完整周期：首末被查询区间截断的周期标 complete=false 且不参与斜率，"
                    + "它们的平均值只覆盖部分天数，不能和中间周期直接比。"
                    + "评分/HRV/入睡潜伏期只有 2026-06-13 之后才有，更早的周期这些字段是 null（缺数据不是 0）。"
                    + "用于回答“我最近睡得有没有变好”“近三个月睡眠时长趋势”“深睡比例在下降吗”“醒来次数越来越多吗”。")
    public Map<String, Object> getSleepTrend(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取睡眠数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取睡眠数据最新一天", required = false) String endDate,
            @McpToolParam(description = "分组粒度：week（默认）或 month；要按天看请用 get_sleep_summary",
                    required = false) String groupBy,
            @McpToolParam(description = "趋势主指标：asleep（默认，平均睡着时长）/ score / efficiency / hrv / "
                    + "wakeCount / deep / latency", required = false) String metric) {
        Map<String, Object> invalid = validateTrend(startDate, endDate, groupBy, metric);
        if (invalid != null) {
            return invalid;
        }
        String mode = normalizeTrendGroup(groupBy);
        DateRange range = sleep.resolveRange(startDate, endDate);
        SleepTrendReport report = sleep.trend(range, mode, SleepTrendMetric.parse(metric));
        if (report.points().isEmpty()) {
            return noData(range);
        }
        int limit = "month".equals(mode) ? MAX_MONTH_POINTS : MAX_WEEK_POINTS;
        if (report.points().size() > limit) {
            String hint = "month".equals(mode)
                    ? "；已经是月粒度了，请缩短区间"
                    : "，或改用 groupBy=month（月粒度可覆盖约 3 年）";
            return invalidArgument("groupBy", groupBy,
                    "按 " + mode + " 聚合会得到 " + report.points().size() + " 个周期，超过上限 "
                            + limit + hint,
                    TREND_GROUPS);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("groupBy", mode);
        result.put("metric", report.trend() == null ? null : report.trend().metric());
        result.put("periodCount", report.points().size());
        result.put("points", report.points());
        result.put("trend", report.trend());
        result.put("overall", report.overall());
        result.put("coverage", report.coverage());
        if (!report.notes().isEmpty()) {
            result.put("notes", report.notes());
        }
        return result;
    }

    /**
     * 趋势的参数校验。
     *
     * <p>{@code groupBy} 不能沿用 {@link #validate} 的宽松口径：那边 {@code day} 是合法值，
     * 这里 {@code day} 等于「退化成 summary」，属于用错了工具，必须明确报错。
     */
    private Map<String, Object> validateTrend(String startDate, String endDate, String groupBy, String metric) {
        if (isPresent(groupBy) && normalizeTrendGroup(groupBy) == null) {
            return invalidArgument("groupBy", groupBy,
                    "趋势只支持按 week 或 month 聚合；按天看睡眠请用 get_sleep_summary（groupBy=day）",
                    TREND_GROUPS);
        }
        if (isPresent(metric) && SleepTrendMetric.parse(metric) == null) {
            return invalidArgument("metric", metric,
                    "无法识别的趋势指标 \"" + metric + "\"；不传默认 asleep（平均睡着时长）",
                    TREND_METRIC_VALUES);
        }
        return validate(startDate, endDate, null);
    }

    /**
     * 趋势分组：不传按 {@code week}；认不出返回 {@code null} 由调用方报错。
     * 与 {@link #normalizeGroup} 分开，是因为这里的合法集合只剩两个值。
     */
    private static String normalizeTrendGroup(String groupBy) {
        if (!isPresent(groupBy)) {
            return "week";
        }
        String key = groupBy.trim().toLowerCase(Locale.ROOT);
        return "week".equals(key) || "month".equals(key) ? key : null;
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
