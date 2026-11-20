package org.example.sporthealth.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.ActivityCoverage;
import org.example.sporthealth.domain.ActivityGoals;
import org.example.sporthealth.domain.ActivityPeriod;
import org.example.sporthealth.domain.ActivitySummary;
import org.example.sporthealth.domain.ActivityTrend;
import org.example.sporthealth.domain.DailyActivityDay;
import org.example.sporthealth.repository.DailyActivityStore;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.stereotype.Service;

/**
 * 日常活动分析：把 {@code daily_activity} 的一天一行拼成区间统计、周期聚合、达标判定与趋势。
 *
 * <p><b>目标值是外生的，不从数据里推</b>：步数 8000、中高强度 30 分钟、站立 12 次（华为默认），
 * 外加 WHO 的每周 150 分钟。这一点很重要 —— 用户问「活动量够不够」，需要的是一个可以对照的
 * 标准，而不是「比上个月多一点」。
 *
 * <p><b>所有均值只在有记录的天上算</b>，不把没记录的天当 0。缺的天数单独由
 * {@link ActivityCoverage} 说明，避免把「三天没戴表」读成「这周只走了 3000 步」。
 *
 * <p><b>趋势用「最近 7 天 vs 前 7 天」的日均值</b>：步数逐日波动主要来自「今天有没有跑步」，
 * 日粒度做回归只会得到锯齿；7 天窗口能同时抹平工作日/周末的差别和单次长距离的影响。
 */
@Service
@Slf4j
public class DailyActivityService {

    /** 步数日目标。华为默认 8000，不是流传更广的 10000（那个数字源自营销）。 */
    static final int STEPS_GOAL = 8000;

    /** 中高强度分钟日目标：WHO 每周 150 分钟摊到七天。 */
    static final int ACTIVE_MINUTES_GOAL = 30;

    /** 站立次数日目标：华为的站立目标就是 12 次（每小时起身一次）。 */
    static final int STAND_GOAL = 12;

    /** 中高强度分钟周目标（WHO）。 */
    static final int WEEKLY_ACTIVE_MINUTES_GOAL = 150;

    /** 低于这个步数算久坐日。 */
    static final int LOW_ACTIVITY_STEPS = 5000;

    /**
     * 「分钟数据少于这么多条」视为可能没戴表 / 导出未完成。
     *
     * <p>正常一天 120~500 条，最少的一次真实记录是 39 条（走了一千多步），
     * 而导出当天的 6 条明显是没过完 —— 20 条能把两者分开。
     */
    static final int SPARSE_MINUTE_ROWS = 20;

    /** 不传日期时的默认窗口：最近 30 天。 */
    private static final int DEFAULT_DAYS = 30;

    /** 趋势窗口：最近 7 天 vs 前 7 天。 */
    private static final int TREND_WINDOW_DAYS = 7;

    /** 变化百分比小于这个幅度算「持平」，避免把正常波动说成趋势。 */
    private static final double TREND_THRESHOLD_PERCENT = 5.0;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private final DailyActivityStore store;
    private final Clock clock;

    public DailyActivityService(DailyActivityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 解析查询区间。
     *
     * <p>不传时取<b>数据最新一天往前 30 天</b>，不是「今天往前 30 天」：库通常落后于当天
     * （导出那天只有几条记录），用今天会把尾巴留成空白，看起来像「最近没戴表」。
     */
    public DateRange resolveRange(String startDate, String endDate) {
        Optional<DateRange> covered = store.coveredRange();
        LocalDate lastDay = covered.map(DateRange::to).orElse(LocalDate.now(clock));
        LocalDate firstDay = covered.map(DateRange::from).orElse(lastDay.minusDays(DEFAULT_DAYS - 1L));

        LocalDate to = isPresent(endDate) ? DateParser.parseDate(endDate.trim()) : lastDay;
        LocalDate from = isPresent(startDate) ? DateParser.parseDate(startDate.trim())
                : to.minusDays(DEFAULT_DAYS - 1L);
        if (!isPresent(startDate) && !isPresent(endDate) && from.isBefore(firstDay)) {
            from = firstDay;
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 晚于结束日期 " + to);
        }
        return new DateRange(from, to);
    }

    /** 区间内的逐日活动，按日期升序；目标达成的三个布尔值在这里按固定目标算好。 */
    public List<DailyActivityDay> days(DateRange range) {
        return store.days(range).stream().map(this::toDay).toList();
    }

    /** 全库有活动的第一天与最后一天，用来解释「这段区间为什么没有数据」。 */
    public Optional<DateRange> coveredRange() {
        return store.coveredRange();
    }

    /**
     * 覆盖计数：分母是区间自然日数，另外数出「没记录」与「记录过少」的天。
     *
     * <p>{@code sparseDays} 与 {@code missingDays} 是两件事：前者表戴了但没戴够（或导出没过完），
     * 后者整天没有数据。两种都不该被当成「这天没走动」。
     */
    public ActivityCoverage coverage(DateRange range, List<DailyActivityDay> days) {
        int sparse = (int) days.stream().filter(d -> d.minuteRows() < SPARSE_MINUTE_ROWS).count();
        LocalDate first = days.isEmpty() ? null : days.get(0).date();
        LocalDate last = days.isEmpty() ? null : days.get(days.size() - 1).date();
        return new ActivityCoverage(range.dayCount(), days.size(),
                range.dayCount() - days.size(), sparse, first, last);
    }

    /** 区间概览：合计、日均、中位数、最高/最低的一天。 */
    public ActivitySummary summary(List<DailyActivityDay> days) {
        if (days.isEmpty()) {
            return new ActivitySummary(0, 0, 0, 0, 0, null, 0, null, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Integer> steps = days.stream().map(DailyActivityDay::steps).sorted().toList();
        DailyActivityDay min = days.stream().min((a, b) -> Integer.compare(a.steps(), b.steps())).orElseThrow();
        DailyActivityDay max = days.stream().max((a, b) -> Integer.compare(a.steps(), b.steps())).orElseThrow();
        long totalSteps = days.stream().mapToLong(DailyActivityDay::steps).sum();
        double totalDistance = days.stream().mapToDouble(DailyActivityDay::distanceKm).sum();
        double totalCalories = days.stream().mapToDouble(DailyActivityDay::caloriesKcal).sum();
        int totalActive = days.stream().mapToInt(DailyActivityDay::activeMinutes).sum();
        double standAvg = days.stream().mapToInt(DailyActivityDay::standEvents).average().orElse(0);
        int n = days.size();
        return new ActivitySummary(
                n,
                totalSteps,
                (int) Math.round((double) totalSteps / n),
                median(steps),
                min.steps(),
                min.date(),
                max.steps(),
                max.date(),
                round(totalDistance, 1),
                round(totalDistance / n, 2),
                round(totalCalories, 1),
                round(totalCalories / n, 1),
                totalActive,
                (int) Math.round((double) totalActive / n),
                round(standAvg, 1),
                (int) days.stream()
                        .filter(d -> d.steps() < LOW_ACTIVITY_STEPS && d.minuteRows() >= SPARSE_MINUTE_ROWS)
                        .count());
    }

    /**
     * 达标情况：三个日目标各达成多少天 + 末尾 7 天的中高强度合计。
     *
     * <p>周目标锚在<b>区间最后一个有记录的日子</b>往前 7 天，不是自然周：用户问「这周够不够」
     * 时关心的是最近七天。窗口内没记录的天不计入合计（也就不会因为缺数据而虚高或虚低）。
     */
    public ActivityGoals goals(List<DailyActivityDay> days) {
        if (days.isEmpty()) {
            return new ActivityGoals(STEPS_GOAL, ACTIVE_MINUTES_GOAL, STAND_GOAL, WEEKLY_ACTIVE_MINUTES_GOAL,
                    0, 0, 0, 0, 0, false);
        }
        LocalDate anchor = days.get(days.size() - 1).date();
        LocalDate windowStart = anchor.minusDays(TREND_WINDOW_DAYS - 1L);
        List<DailyActivityDay> window = days.stream()
                .filter(d -> !d.date().isBefore(windowStart) && !d.date().isAfter(anchor))
                .toList();
        int weekly = window.stream().mapToInt(DailyActivityDay::activeMinutes).sum();
        return new ActivityGoals(
                STEPS_GOAL,
                ACTIVE_MINUTES_GOAL,
                STAND_GOAL,
                WEEKLY_ACTIVE_MINUTES_GOAL,
                (int) days.stream().filter(DailyActivityDay::stepsGoalMet).count(),
                (int) days.stream().filter(DailyActivityDay::activeMinutesGoalMet).count(),
                (int) days.stream().filter(DailyActivityDay::standGoalMet).count(),
                window.size(),
                weekly,
                window.size() == TREND_WINDOW_DAYS && weekly >= WEEKLY_ACTIVE_MINUTES_GOAL);
    }

    /**
     * 按周或月聚合。
     *
     * <p>周期边界自己算（周 = 周一到周日，月 = 1 号到月末），这样才判得出首末周期是否被
     * 查询区间截断（{@code complete}）。没有记录的周期不输出，断档在返回值里表现为相邻周期跳开。
     */
    public List<ActivityPeriod> periods(List<DailyActivityDay> days, DateRange range, String groupBy) {
        Map<String, List<DailyActivityDay>> buckets = new LinkedHashMap<>();
        for (DailyActivityDay day : days) {
            buckets.computeIfAbsent(periodKey(day.date(), groupBy), k -> new ArrayList<>()).add(day);
        }
        List<ActivityPeriod> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, List<DailyActivityDay>> entry : buckets.entrySet()) {
            List<DailyActivityDay> bucket = entry.getValue();
            LocalDate bucketFrom = periodStart(bucket.get(0).date(), groupBy);
            LocalDate bucketTo = periodEnd(bucket.get(0).date(), groupBy);
            boolean complete = !bucketFrom.isBefore(range.from()) && !bucketTo.isAfter(range.to());
            long steps = bucket.stream().mapToLong(DailyActivityDay::steps).sum();
            int n = bucket.size();
            out.add(new ActivityPeriod(
                    entry.getKey(),
                    bucketFrom,
                    bucketTo,
                    complete,
                    n,
                    steps,
                    (int) Math.round((double) steps / n),
                    round(bucket.stream().mapToDouble(DailyActivityDay::distanceKm).sum(), 1),
                    round(bucket.stream().mapToDouble(DailyActivityDay::caloriesKcal).sum(), 1),
                    round(bucket.stream().mapToInt(DailyActivityDay::activeMinutes).average().orElse(0), 1),
                    round(bucket.stream().mapToInt(DailyActivityDay::standEvents).average().orElse(0), 1),
                    (int) bucket.stream().filter(DailyActivityDay::stepsGoalMet).count(),
                    (int) bucket.stream().filter(DailyActivityDay::activeMinutesGoalMet).count()));
        }
        return out;
    }

    /**
     * 四个指标的「最近 7 天 vs 前 7 天」。
     *
     * <p>键为 {@code steps} / {@code distanceKm} / {@code activeMinutes} / {@code standEvents}。
     * 任一侧没有记录时 {@code change} 为 null、{@code direction} 为 {@code unknown}
     * —— 「看不出」不是「没变化」。
     */
    public Map<String, ActivityTrend> trends(List<DailyActivityDay> days) {
        Map<String, ActivityTrend> out = new LinkedHashMap<>();
        if (days.isEmpty()) {
            return out;
        }
        LocalDate anchor = days.get(days.size() - 1).date();
        LocalDate recentFrom = anchor.minusDays(TREND_WINDOW_DAYS - 1L);
        LocalDate previousTo = recentFrom.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(TREND_WINDOW_DAYS - 1L);

        List<DailyActivityDay> recent = days.stream()
                .filter(d -> !d.date().isBefore(recentFrom) && !d.date().isAfter(anchor)).toList();
        List<DailyActivityDay> previous = days.stream()
                .filter(d -> !d.date().isBefore(previousFrom) && !d.date().isAfter(previousTo)).toList();

        putTrend(out, "steps", "步",
                recent, previous, d -> (double) d.steps());
        putTrend(out, "distanceKm", "公里",
                recent, previous, DailyActivityDay::distanceKm);
        putTrend(out, "activeMinutes", "分钟",
                recent, previous, d -> (double) d.activeMinutes());
        putTrend(out, "standEvents", "次",
                recent, previous, d -> (double) d.standEvents());
        return out;
    }

    /**
     * 给调用方看的读法提示。
     *
     * <p>只在真出现这类情况时才说，不制造噪音：缺数据 / 记录过少、久坐日、
     * 周线未达标、步数中位数与均值差得多、趋势看不出来。
     * 三段口径说明（站立按次数、热量是活动热量、步数含双计）放在最后，
     * 因为它们永远成立，属于「读之前必须先知道」的那类。
     */
    public List<String> notes(DateRange range, ActivityCoverage coverage,
                              ActivitySummary summary, ActivityGoals goals,
                              Map<String, ActivityTrend> trends) {
        List<String> notes = new ArrayList<>();

        if (coverage.missingDays() > 0) {
            notes.add("区间 " + range.text() + " 共 " + coverage.days() + " 天，其中 "
                    + coverage.missingDays() + " 天没有活动记录；所有日均/达标天数只按有记录的 "
                    + coverage.recordedDays() + " 天算，不能当成整段区间的日均。");
        }
        if (coverage.sparseDays() > 0) {
            notes.add("有 " + coverage.sparseDays() + " 天的分钟数据不足 " + SPARSE_MINUTE_ROWS
                    + " 条（正常一天 120~500 条），这些天多半是没戴表或导出那天还没过完，"
                    + "步数会异常低，判「活动量不够」前先排除。");
        }
        if (summary.days() > 1) {
            int gap = summary.averageSteps() - summary.medianSteps();
            if (gap > 1500) {
                notes.add("日均 " + summary.averageSteps() + " 步比中位数 " + summary.medianSteps()
                        + " 步高 " + gap + " 步，说明有几天特别多（" + summary.maxStepsDate() + " 走了 "
                        + summary.maxSteps() + " 步）把均值抬上去了；「平常一天走多少」看中位数更准。");
            }
            if (summary.lowActivityDays() > 0) {
                notes.add("有 " + summary.lowActivityDays() + " 天步数低于 " + LOW_ACTIVITY_STEPS
                        + " 步（久坐日），占 " + percent(summary.lowActivityDays(), summary.days()) + "。");
            }
        }
        if (summary.days() > 1) {
            notes.add("达标情况：步数 ≥" + goals.stepsPerDay() + " 的天数 " + goals.daysMeetingSteps()
                    + " / " + summary.days() + "，中高强度 ≥" + goals.activeMinutesPerDay() + " 分钟 "
                    + goals.daysMeetingActiveMinutes() + " / " + summary.days() + "，站立 ≥"
                    + goals.standEventsPerDay() + " 次 " + goals.daysMeetingStand() + " / "
                    + summary.days() + "。");
        }
        if (goals.weeklyActiveMinutesDays() < TREND_WINDOW_DAYS) {
            notes.add("区间末尾 7 天里只有 " + goals.weeklyActiveMinutesDays() + " 天有记录"
                    + "（中高强度合计 " + goals.weeklyActiveMinutes() + " 分钟）：窗口不满 "
                    + TREND_WINDOW_DAYS + " 天，不能据此判断是否达到 WHO 每周 "
                    + goals.activeMinutesPerWeek() + " 分钟的基线，要问周线请查至少 7 天。");
        } else {
            notes.add("区间末尾 7 天中高强度合计 " + goals.weeklyActiveMinutes() + " 分钟，"
                    + (goals.weeklyActiveMinutesMet() ? "已达到" : "未达到")
                    + " WHO 的每周 " + goals.activeMinutesPerWeek() + " 分钟基线。"
                    + "注意这是「最近 7 天」而不是自然周，且缺记录的天不计入。");
        }

        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, ActivityTrend> entry : trends.entrySet()) {
            if ("unknown".equals(entry.getValue().direction())) {
                unknown.add(entry.getKey());
            }
        }
        if (!unknown.isEmpty()) {
            notes.add("以下指标最近 7 天或前 7 天缺记录，暂不做对比：" + String.join("、", unknown)
                    + "；direction=unknown 是「看不出变化」，不是「没变化」。");
        }

        notes.add("口径：站立（standEvents）是「站立事件条数」，不是分钟数 —— 华为把站立记成整点事件，"
                + "一天 " + goals.standEventsPerDay() + " 次就是满分；热量（caloriesKcal）是活动热量，"
                + "不含基础代谢，不等于全天总消耗；步数会把同一分钟的走路 + 跑步两条记录各算一次，"
                + "比华为 App 自报高约 0.8%。");
        return notes;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 仓储行 → 对外的一天。距离在这里换成公里，三个达标布尔值在这里按固定目标判定
     * —— 目标只有一处（本类的常量），工具层不再重复一遍。
     */
    private DailyActivityDay toDay(DailyActivityStore.Row row) {
        return new DailyActivityDay(
                row.date(),
                row.steps(),
                row.walkSteps(),
                row.runSteps(),
                round(row.distanceMeters() / 1000d, 2),
                round(row.caloriesKcal(), 1),
                row.activeMinutes(),
                row.standEvents(),
                row.minuteRows(),
                row.steps() >= STEPS_GOAL,
                row.activeMinutes() >= ACTIVE_MINUTES_GOAL,
                row.standEvents() >= STAND_GOAL);
    }

    private static void putTrend(Map<String, ActivityTrend> out, String key, String unit,
                                 List<DailyActivityDay> recent, List<DailyActivityDay> previous,
                                 Function<DailyActivityDay, Double> extractor) {
        Double recentMean = mean(recent, extractor);
        Double previousMean = mean(previous, extractor);
        Double changePercent = null;
        if (recentMean != null && previousMean != null && previousMean != 0) {
            changePercent = round((recentMean - previousMean) / previousMean * 100d, 1);
        }
        out.put(key, new ActivityTrend(unit, recent.size(), previous.size(),
                round(recentMean, 1), round(previousMean, 1),
                changePercent, direction(changePercent)));
    }

    private static Double mean(List<DailyActivityDay> days,
                               Function<DailyActivityDay, Double> extractor) {
        if (days.isEmpty()) {
            return null;
        }
        return days.stream().map(extractor).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** 变化百分比的方向：±5% 以内算持平。 */
    private static String direction(Double changePercent) {
        if (changePercent == null) {
            return "unknown";
        }
        if (changePercent > TREND_THRESHOLD_PERCENT) {
            return "up";
        }
        if (changePercent < -TREND_THRESHOLD_PERCENT) {
            return "down";
        }
        return "flat";
    }

    /** 中位数：偶数个取中间两个的平均后四舍五入。 */
    private static int median(List<Integer> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (int) Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d);
    }

    private static String percent(int part, int total) {
        if (total <= 0) {
            return "0%";
        }
        return Math.round(part * 100d / total) + "%";
    }

    /** 周期标识：周用 ISO 周（{@code 2026-W45}），月用 {@code yyyy-MM}。 */
    private static String periodKey(LocalDate date, String groupBy) {
        if ("month".equals(groupBy)) {
            return MONTH_FORMAT.format(date);
        }
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                + String.format(Locale.ROOT, "%02d", monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static LocalDate periodStart(LocalDate date, String groupBy) {
        return "month".equals(groupBy)
                ? date.withDayOfMonth(1)
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate periodEnd(LocalDate date, String groupBy) {
        return "month".equals(groupBy)
                ? date.with(TemporalAdjusters.lastDayOfMonth())
                : date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    private static Double round(Double value, int digits) {
        if (value == null || value.isNaN()) {
            return null;
        }
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
