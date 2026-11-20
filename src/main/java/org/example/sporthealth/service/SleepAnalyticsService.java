package org.example.sporthealth.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.SleepCoverage;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepPeriod;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.repository.SleepStore;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.DateRangeResolver;
import org.example.sporthealth.support.SleepFormat;
import org.springframework.stereotype.Service;

/**
 * 睡眠分析：把「分期」和「官方报告」两套来源拼成每个夜晚一行，再算区间汇总。
 *
 * <p><b>为什么不能只用一张表</b>：
 * <ul>
 *   <li>{@code sleep_report} 只有 2026-06-13 之后的数据，评分/效率/HRV/血氧/潜伏期只在这里；</li>
 *   <li>{@code sleep_session} 覆盖全部历史，但只有时长与分期，没有评分。</li>
 * </ul>
 * 于是「评分是 null」既可能是「那个年代还没有报告」，也可能是「这台设备那晚没上报」。
 * 这层不猜，直接把来源写进 {@code sources}，并在 {@link SleepCoverage} 里给出计数，
 * 让调用方（和 LLM）能分清「缺数据」和「真的 0 分」。
 *
 * <p><b>哨兵值</b>：报告里的 {@code sleep_efficiency = 255}、{@code avg_oxygen_saturation = -1}、
 * {@code deep_part = 255}、午睡行的 {@code sleep_score = 0} 都是「没有有效值」的占位，
 * 在这里统一折成 {@code null}，不让它们参与平均。
 *
 * <p><b>醒来次数</b>：华为报告的 {@code wakeCount} 恒为 0（设备没上报），只能用原始分期还原：
 * 把相邻（间隔 ≤1 分钟）的清醒分钟粘成一次「醒来」，再去掉贴着头尾的两段——贴着会话开始的
 * 是入睡潜伏期，贴着会话结束的是起床前那一段，二者都不是「夜里醒来」。剩下的段里，
 * 时长 <2 分钟的当作翻身级别的短暂觉醒，不计入次数。
 */
@Service
@Slf4j
public class SleepAnalyticsService {

    /** 两段清醒之间只要隔了 ≤1 分钟睡眠仍然算同一次醒来（分期是逐分钟的，正常会完全相邻）。 */
    private static final int WAKE_MERGE_GAP_MINUTES = 1;
    /** 短于这个时长的清醒段不算一次「醒来」。 */
    private static final int MIN_WAKE_EPISODE_MINUTES = 2;
    /** 报告里睡眠效率的哨兵值是 255，正常值不会超过 100。 */
    private static final double MAX_PERCENT = 100d;
    /** 入睡潜伏期的合理上界（分钟）；午睡行的 1092 是哨兵值。 */
    private static final double MAX_LATENCY_MINUTES = 600d;
    /** HRV 的合理上界（毫秒），再大基本可以确定是脏数据。 */
    private static final double MAX_HRV_MS = 300d;
    /** 血氧的合理下界（%）；-1 与 0 都是「没测」。 */
    private static final double MIN_SPO2_PERCENT = 50d;

    /** 分期在 {@code health_metric.metric_key} 里的名字。 */
    private static final String DEEP_KEY = "PROFESSIONAL_SLEEP_DEEP";
    private static final String LIGHT_KEY = "PROFESSIONAL_SLEEP_SHALLOW";
    private static final String REM_KEY = "PROFESSIONAL_SLEEP_DREAM";

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SleepStore store;
    private final DateRangeResolver rangeResolver;

    public SleepAnalyticsService(SleepStore store, DateRangeResolver rangeResolver) {
        this.store = store;
        this.rangeResolver = rangeResolver;
    }

    /**
     * 解析查询区间；两端都不传时用睡眠数据自身的区间（不是跑步数据的，
     * 睡眠比跑步早开始，用跑步的区间会截掉前面几个月）。
     */
    public DateRange resolveRange(String startDate, String endDate) {
        Optional<DateRange> covered = store.coveredRange();
        return rangeResolver.resolve(startDate, endDate,
                covered.map(DateRange::from).orElse(null),
                covered.map(DateRange::to).orElse(null));
    }

    /** 睡眠数据覆盖的整体区间，用于「区间内没数据」时给提示。 */
    public Optional<DateRange> coveredRange() {
        return store.coveredRange();
    }

    /**
     * 区间内每个夜晚一行，按日期升序。
     *
     * <p>夜晚的集合是「有夜间会话」与「有评分报告」的并集：只有会话的夜晚来自分期，
     * 只有报告的夜晚（分期把夜睡和午睡粘成了一段、被标成 NAP 的情形，全库只有几天）来自报告。
     */
    public List<SleepNight> nights(DateRange range) {
        Map<LocalDate, SleepStore.NightSession> sessions = pickOnePerDay(
                store.nightSessions(range), SleepStore.NightSession::date,
                SleepStore.NightSession::asleepMinutes);
        Map<LocalDate, SleepStore.ScoredReport> reports = pickOnePerDay(
                store.scoredReports(range), SleepStore.ScoredReport::date,
                report -> report.end() == null || report.start() == null ? 0
                        : (int) Duration.between(report.start(), report.end()).toMinutes());

        TreeSet<LocalDate> dates = new TreeSet<>(sessions.keySet());
        dates.addAll(reports.keySet());

        List<SleepStore.Span> spans = store.wakeSpans(range);

        List<SleepNight> nights = new ArrayList<>(dates.size());
        for (LocalDate day : dates) {
            SleepStore.NightSession session = sessions.get(day);
            SleepStore.ScoredReport report = reports.get(day);
            LocalDateTime windowStart = session != null ? session.start() : report.start();
            LocalDateTime windowEnd = session != null ? session.end() : report.end();
            WakeStats wake = wakeStats(spans, windowStart, windowEnd);

            int timeInBed = (int) Duration.between(windowStart, windowEnd).toMinutes();
            Integer deep = null;
            Integer light = null;
            Integer rem = null;
            int asleep;
            List<String> sources = new ArrayList<>(2);
            if (session != null) {
                asleep = session.asleepMinutes();
                deep = session.deepMinutes();
                light = session.lightMinutes();
                rem = session.remMinutes();
                sources.add("stages");
            } else {
                // 没有夜间会话（那天的分期被午睡粘住、整段标成了 NAP），用报告自己的窗口重新框一遍分期
                Map<String, Integer> stages = store.stageMinutesInWindow(windowStart, windowEnd);
                deep = stages.get(DEEP_KEY);
                light = stages.get(LIGHT_KEY);
                rem = stages.get(REM_KEY);
                if (deep != null && light != null && rem != null) {
                    asleep = deep + light + rem;
                    sources.add("stages");
                } else {
                    asleep = asleepFromReport(report, timeInBed);
                }
            }
            if (report != null) {
                sources.add("report");
            }

            nights.add(new SleepNight(
                    day,
                    SleepFormat.dateTime(windowStart),
                    SleepFormat.dateTime(windowEnd),
                    timeInBed,
                    asleep,
                    wake.awakeMinutes(),
                    wake.episodes(),
                    deep,
                    light,
                    rem,
                    report == null ? null : intOrNull(latencyMinutes(report)),
                    report == null ? null : percent(report.efficiency()),
                    report == null ? null : score(report.score()),
                    report == null ? null : hrv(report.hrv()),
                    report == null ? null : spo2(report.spo2()),
                    List.copyOf(sources)));
        }
        return nights;
    }

    /**
     * 区间汇总。每个平均值只在自己的非空子集上求，计数一并给出；
     * {@code nights} 是全部夜晚数，不做任何 0 值填充。
     */
    public SleepSummary summarize(List<SleepNight> nights) {
        if (nights == null || nights.isEmpty()) {
            return new SleepSummary(0, null, null, null, null, null, null, null, null,
                    null, 0, null, null, 0, null, 0, 0);
        }
        IntAvg asleep = new IntAvg();
        IntAvg inBed = new IntAvg();
        IntAvg awake = new IntAvg();
        DoubleAvg wakeCount = new DoubleAvg();
        DoubleAvg latency = new DoubleAvg();
        DoubleAvg score = new DoubleAvg();
        DoubleAvg efficiency = new DoubleAvg();
        DoubleAvg hrv = new DoubleAvg();
        DoubleAvg spo2 = new DoubleAvg();
        long deepSum = 0;
        long remSum = 0;
        long stageAsleepSum = 0;
        int stageNights = 0;

        for (SleepNight night : nights) {
            asleep.add(night.asleepMinutes());
            inBed.add(night.timeInBedMinutes());
            awake.add(night.awakeMinutes());
            wakeCount.add(night.wakeCount() == null ? null : night.wakeCount().doubleValue());
            latency.add(night.sleepLatencyMinutes() == null ? null : night.sleepLatencyMinutes().doubleValue());
            score.add(night.score());
            efficiency.add(night.efficiencyPercent());
            hrv.add(night.hrvMs());
            spo2.add(night.spo2Percent());
            if (night.hasStages() && night.deepMinutes() != null && night.remMinutes() != null
                    && night.asleepMinutes() > 0) {
                deepSum += night.deepMinutes();
                remSum += night.remMinutes();
                stageAsleepSum += night.asleepMinutes();
                stageNights++;
            }
        }

        Integer avgAsleep = asleep.average();
        return new SleepSummary(
                nights.size(),
                avgAsleep,
                SleepFormat.hoursAndMinutes(avgAsleep),
                inBed.average(),
                awake.average(),
                wakeCount.average(),
                latency.average(),
                stageAsleepSum == 0 ? null : round(deepSum * 100d / stageAsleepSum, 1),
                stageAsleepSum == 0 ? null : round(remSum * 100d / stageAsleepSum, 1),
                score.average(),
                score.count(),
                efficiency.average(),
                hrv.average(),
                hrv.count(),
                spo2.average(),
                spo2.count(),
                stageNights);
    }

    /** 覆盖情况：哪些夜晚有分期、哪些有报告、血氧到底几个夜晚有值。 */
    public SleepCoverage coverage(List<SleepNight> nights) {
        int total = nights == null ? 0 : nights.size();
        int withStages = 0;
        int withReport = 0;
        int withScore = 0;
        int withSpo2 = 0;
        for (SleepNight night : nights == null ? List.<SleepNight>of() : nights) {
            if (night.hasStages()) {
                withStages++;
            }
            if (night.hasReport()) {
                withReport++;
            }
            if (night.score() != null) {
                withScore++;
            }
            if (night.spo2Percent() != null) {
                withSpo2++;
            }
        }
        return new SleepCoverage(total, withStages, withReport, total - withReport,
                withScore, withSpo2, note(total, total - withReport, withSpo2));
    }

    /**
     * 按周 / 月聚合。
     *
     * @param groupBy {@code week} 或 {@code month}；{@code day} 由调用方直接用 {@link #nights(DateRange)}
     */
    public List<SleepPeriod> periods(List<SleepNight> nights, String groupBy) {
        if (nights == null || nights.isEmpty() || groupBy == null || "day".equals(groupBy)) {
            return List.of();
        }
        Map<String, List<SleepNight>> buckets = new LinkedHashMap<>();
        Map<String, LocalDate[]> bounds = new HashMap<>();
        for (SleepNight night : nights) {
            LocalDate day = night.date();
            String key = bucketKey(day, groupBy);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(night);
            LocalDate[] bound = bounds.computeIfAbsent(key, k -> new LocalDate[] {
                    day, day });
            if (day.isBefore(bound[0])) {
                bound[0] = day;
            }
            if (day.isAfter(bound[1])) {
                bound[1] = day;
            }
        }
        List<SleepPeriod> periods = new ArrayList<>(buckets.size());
        buckets.forEach((key, group) -> {
            SleepSummary summary = summarize(group);
            LocalDate[] bound = bounds.get(key);
            periods.add(new SleepPeriod(key, bound[0], bound[1], group.size(),
                    summary.avgAsleepMinutes(), summary.avgAsleepText(),
                    summary.avgScore(), summary.reportNights(), summary.avgEfficiencyPercent(),
                    summary.avgHrvMs(), summary.avgSpo2Percent(), summary.avgWakeCount(),
                    summary.spo2Nights()));
        });
        periods.sort(Comparator.comparing(SleepPeriod::period));
        return periods;
    }

    /**
     * 既没有夜间会话、窗口内也框不出完整分期的兜底：按睡眠效率反推睡着时长。
     *
     * <p>睡眠效率的定义就是「睡着 / 卧床」，所以 {@code 卧床 × 效率} 是最接近的口径；
     * 效率也没有就退回「卧床 - 潜伏期」，再不行退回卧床时长，总之不返回 0。
     *
     * <p>不用 {@code fallAsleepTime}：在这份导出里它恒等于 {@code start_time}（145 条实测全等），
     * 拿它减 {@code risingTime} 只会得到整段卧床时长。
     */
    private static int asleepFromReport(SleepStore.ScoredReport report, int timeInBed) {
        Double efficiency = percent(report.efficiency());
        if (efficiency != null) {
            return (int) Math.max(0, Math.round(timeInBed * efficiency / 100d));
        }
        Double latency = latencyMinutes(report);
        if (latency != null) {
            return (int) Math.max(0, Math.round(timeInBed - latency));
        }
        return Math.max(0, timeInBed);
    }

    /** 醒来统计：总清醒分钟 + 醒来次数。 */
    private record WakeStats(int awakeMinutes, int episodes) {
    }

    /**
     * 从一个睡眠窗口里挑出清醒段，粘成「醒来」次数。
     *
     * <p>贴着头、尾的两段既不算次数也不算清醒时长：会话是从第一段分期（可能就是清醒）开始记录的，
     * 开头那段是躺下没睡着的时间（<b>就是报告的入睡潜伏期</b>），结尾那段是起床前的清醒。
     * 所以这里的 {@code awakeMinutes} 是 WASO 口径（睡着之后的清醒），
     * 与卧床时长之间还差「潜伏期 + 起床前清醒」。
     */
    private static WakeStats wakeStats(List<SleepStore.Span> spans, LocalDateTime windowStart,
                                       LocalDateTime windowEnd) {
        if (spans == null || spans.isEmpty() || windowStart == null || windowEnd == null) {
            return new WakeStats(0, 0);
        }
        List<SleepStore.Span> inside = new ArrayList<>();
        int index = lowerBound(spans, windowStart);
        while (index < spans.size() && !spans.get(index).start().isAfter(windowEnd)) {
            SleepStore.Span span = spans.get(index);
            if (!span.end().isBefore(windowStart)) {
                inside.add(span);
            }
            index++;
        }
        if (inside.isEmpty()) {
            return new WakeStats(0, 0);
        }

        // 相邻的清醒段粘成一次醒来
        List<LocalDateTime[]> episodes = new ArrayList<>();
        for (SleepStore.Span span : inside) {
            LocalDateTime[] last = episodes.isEmpty() ? null : episodes.get(episodes.size() - 1);
            boolean merge = last != null
                    && Duration.between(last[1], span.start()).toMinutes() <= WAKE_MERGE_GAP_MINUTES;
            if (merge) {
                last[1] = span.end();
            } else {
                episodes.add(new LocalDateTime[] {span.start(), span.end()});
            }
        }

        int awakeMinutes = 0;
        int count = 0;
        for (int i = 0; i < episodes.size(); i++) {
            LocalDateTime start = episodes.get(i)[0];
            LocalDateTime end = episodes.get(i)[1];
            boolean touchesStart = i == 0 && !start.isAfter(windowStart);
            boolean touchesEnd = i == episodes.size() - 1 && !end.isBefore(windowEnd);
            if (touchesStart || touchesEnd) {
                continue;
            }
            long minutes = Duration.between(start, end).toMinutes();
            awakeMinutes += (int) minutes;
            // 1 分钟的清醒更像翻身级别的短暂觉醒，不单独算一次「醒来」
            if (minutes >= MIN_WAKE_EPISODE_MINUTES) {
                count++;
            }
        }
        return new WakeStats(awakeMinutes, count);
    }

    /** 第一个 start 不早于 {@code time} 的下标。 */
    private static int lowerBound(List<SleepStore.Span> spans, LocalDateTime time) {
        int low = 0;
        int high = spans.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (spans.get(mid).start().isBefore(time)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 同一天多行时留一行：{@code rank} 大的胜出（夜间会话按睡着时长，报告按时长）。 */
    private static <T> Map<LocalDate, T> pickOnePerDay(List<T> rows,
                                                       Function<T, LocalDate> dayOf,
                                                       java.util.function.ToIntFunction<T> rank) {
        Map<LocalDate, T> map = new HashMap<>();
        for (T row : rows) {
            LocalDate day = dayOf.apply(row);
            if (day == null) {
                continue;
            }
            T current = map.get(day);
            if (current == null || rank.applyAsInt(row) > rank.applyAsInt(current)) {
                map.put(day, row);
            }
        }
        return map;
    }

    private static String bucketKey(LocalDate day, String groupBy) {
        if ("month".equals(groupBy)) {
            return MONTH_KEY.format(day);
        }
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = monday.get(IsoFields.WEEK_BASED_YEAR);
        return String.format("%d-W%02d", year, week);
    }

    private static String note(int nights, int stageOnly, int spo2Nights) {
        if (nights == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>(2);
        if (stageOnly > 0) {
            parts.add("其中 " + stageOnly + " 个夜晚只有睡眠分期、没有睡眠报告（报告从 2026-06-13 起才有），"
                    + "这些夜晚的 score/efficiency/hrv/spo2/sleepLatency 是 null，表示缺数据而不是 0");
        }
        if (spo2Nights < nights) {
            parts.add("血氧只有 " + spo2Nights + "/" + nights + " 个夜晚上报了值，其余为 null（设备常年不测）");
        }
        return parts.isEmpty() ? null : String.join("；", parts) + "。";
    }

    // ---- 哨兵值归一：全部折成 null，让平均值自动跳过 ----

    private static Double score(Double value) {
        return value == null || value <= 0 || value > MAX_PERCENT ? null : value;
    }

    private static Double percent(Double value) {
        return value == null || value < 0 || value > MAX_PERCENT ? null : value;
    }

    private static Double hrv(Double value) {
        return value == null || value <= 0 || value > MAX_HRV_MS ? null : value;
    }

    private static Double spo2(Double value) {
        return value == null || value < MIN_SPO2_PERCENT || value > MAX_PERCENT ? null : value;
    }

    private static Double latencyMinutes(SleepStore.ScoredReport report) {
        Double value = report.latency();
        return value == null || value < 0 || value > MAX_LATENCY_MINUTES ? null : value;
    }

    private static Integer intOrNull(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static Double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    /** 整分钟均值：只统计非空值，全部为空时返回 null。 */
    private static final class IntAvg {
        private long sum;
        private int count;

        void add(Integer value) {
            if (value != null) {
                sum += value;
                count++;
            }
        }

        Integer average() {
            return count == 0 ? null : (int) Math.round((double) sum / count);
        }
    }

    /** 小数均值：只统计非空值，全部为空时返回 null。 */
    private static final class DoubleAvg {
        private double sum;
        private int count;

        void add(Double value) {
            if (value != null) {
                sum += value;
                count++;
            }
        }

        Double average() {
            return count == 0 ? null : round(sum / count, 1);
        }

        int count() {
            return count;
        }
    }
}
