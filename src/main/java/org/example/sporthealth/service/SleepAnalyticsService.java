package org.example.sporthealth.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import org.example.sporthealth.domain.SleepDetailCoverage;
import org.example.sporthealth.domain.SleepDetailNight;
import org.example.sporthealth.domain.SleepFragments;
import org.example.sporthealth.domain.SleepInterval;
import org.example.sporthealth.domain.SleepNap;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepPeriod;
import org.example.sporthealth.domain.SleepReportExtras;
import org.example.sporthealth.domain.SleepStageSegment;
import org.example.sporthealth.domain.SleepStageTotals;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.domain.SleepTrend;
import org.example.sporthealth.domain.SleepTrendDeltas;
import org.example.sporthealth.domain.SleepTrendMetric;
import org.example.sporthealth.domain.SleepTrendPoint;
import org.example.sporthealth.domain.SleepTrendReport;
import org.example.sporthealth.domain.SleepTrendValue;
import org.example.sporthealth.domain.SleepTrendValues;
import org.example.sporthealth.repository.SleepStore;
import org.example.sporthealth.support.DateParser;
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

    /** 时间线最多返回多少段（实测一夜合并后中位数 43 段、最多 75 段，240 只是防脏数据）。 */
    private static final int MAX_TIMELINE_SEGMENTS = 240;

    /**
     * 报告里的 epoch 秒按设备时区还原成墙上时间。
     *
     * <p>{@code sleep_report} 没有存时区，全库 {@code sample_sequence.time_zone} 只有一个取值
     * {@code +0800}，所以这里固定 +08:00；换成 {@code systemDefault()} 反而会在服务器跑在 UTC
     * 时把所有报告时刻整体挪 8 小时。
     */
    private static final ZoneOffset ZONE_OFFSET = ZoneOffset.ofHours(8);

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
     * 睡眠详情：在 {@link #nights(DateRange)} 的基础上补时间线、醒来时刻、碎片与报告细节。
     *
     * <p>基础字段直接复用 {@code nights()} 的结果，保证 detail 与 summary 的数字完全一致
     * ——详情视图最忌讳「换个工具就换个算法」，那样两个答案都没人敢用。
     *
     * <p>三份原始数据各管一段，互不替代：
     * <ul>
     *   <li>{@code health_metric} 的分期（type=9）：时间线与阶段合计，覆盖全部历史；</li>
     *   <li>{@code sleep_report}：评分/效率/HRV/血氧/心率/呼吸率，2026-06-13 起；</li>
     *   <li>{@code sample_sequence} type=700008 的碎片：设备认定的连续睡眠块与块间间隙，2026-06-12 起。</li>
     * </ul>
     *
     * @param includeTimeline 是否返回逐段的时间线（false 时 {@code timeline} 为空数组，段数照给）
     */
    public List<SleepDetailNight> details(DateRange range, boolean includeTimeline) {
        List<SleepNight> base = nights(range);
        if (base.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, SleepStore.ScoredReport> reports = pickOnePerDay(
                store.scoredReports(range), SleepStore.ScoredReport::date,
                report -> report.end() == null || report.start() == null ? 0
                        : (int) Duration.between(report.start(), report.end()).toMinutes());
        List<SleepStore.Span> wakeSpans = store.wakeSpans(range);
        List<SleepStore.StageSpan> stageSpans = store.stageSpans(
                range.from().minusDays(1).atStartOfDay(), range.to().plusDays(2).atStartOfDay());
        List<SleepStore.FragmentSpan> fragments = store.fragments(range);
        List<SleepStore.Span> noons = store.noonSpans(range);

        List<SleepDetailNight> details = new ArrayList<>(base.size());
        for (SleepNight night : base) {
            LocalDateTime windowStart = DateParser.parseDateTime(night.bedtime());
            LocalDateTime windowEnd = DateParser.parseDateTime(night.waketime());
            List<SleepStore.StageSpan> stages = stagesWithin(stageSpans, windowStart, windowEnd);

            List<SleepStageSegment> timeline = mergeStages(stages);
            int segments = timeline.size();
            boolean truncated = includeTimeline && segments > MAX_TIMELINE_SEGMENTS;
            if (truncated) {
                timeline = timeline.subList(0, MAX_TIMELINE_SEGMENTS);
            }
            if (!includeTimeline) {
                timeline = List.of();
            }

            List<SleepStageSegment> episodes = new ArrayList<>();
            for (SleepStore.Span episode : wakeEpisodes(wakeSpans, windowStart, windowEnd)) {
                episodes.add(new SleepStageSegment("wake",
                        SleepFormat.dateTime(episode.start()), SleepFormat.dateTime(episode.end()),
                        episode.minutes()));
            }

            SleepFragments fragmentsBlock = fragmentsFor(
                    fragmentsWithin(fragments, windowStart, windowEnd));

            List<String> sources = new ArrayList<>(night.sources());
            if (fragmentsBlock.available() && !sources.contains("fragments")) {
                sources.add("fragments");
            }

            details.add(new SleepDetailNight(
                    night.date(),
                    night.bedtime(),
                    night.waketime(),
                    night.timeInBedMinutes(),
                    night.asleepMinutes(),
                    night.awakeMinutes(),
                    night.wakeCount(),
                    night.sleepLatencyMinutes(),
                    night.efficiencyPercent(),
                    night.score(),
                    night.hrvMs(),
                    night.spo2Percent(),
                    stageTotals(stages, night.asleepMinutes()),
                    timeline,
                    segments,
                    truncated,
                    episodes,
                    fragmentsBlock,
                    reportExtras(reports.get(night.date())),
                    napBefore(noons, windowStart),
                    List.copyOf(sources)));
        }
        return details;
    }

    /** 详情的数据覆盖计数：哪些夜晚有时间线、哪些有报告、哪些有碎片。 */
    public SleepDetailCoverage detailCoverage(List<SleepDetailNight> nights) {
        int total = nights == null ? 0 : nights.size();
        int withStages = 0;
        int withReport = 0;
        int withFragments = 0;
        for (SleepDetailNight night : nights == null ? List.<SleepDetailNight>of() : nights) {
            if (night.timelineSegments() > 0) {
                withStages++;
            }
            if (night.report() != null) {
                withReport++;
            }
            if (night.fragments() != null && night.fragments().available()) {
                withFragments++;
            }
        }
        List<String> parts = new ArrayList<>(2);
        if (withReport < total) {
            parts.add("其中 " + (total - withReport) + " 个夜晚没有官方报告（报告从 2026-06-13 起才有），"
                    + "score/efficiency/hrv/spo2/sleepLatency 与 report 块是 null");
        }
        if (withFragments < total) {
            parts.add("其中 " + (total - withFragments) + " 个夜晚没有睡眠碎片（碎片从 2026-06-12 起才有），"
                    + "fragments.available 为 false");
        }
        return new SleepDetailCoverage(total, withStages, withReport, withFragments,
                parts.isEmpty() ? null : String.join("；", parts) + "。");
    }

    /** 窗口内的分期段（窗口按 {@code start} 归属，避免前后夜互相吃段）。 */
    private static List<SleepStore.StageSpan> stagesWithin(List<SleepStore.StageSpan> spans,
                                                          LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (spans == null || spans.isEmpty() || windowStart == null || windowEnd == null) {
            return List.of();
        }
        List<SleepStore.StageSpan> inside = new ArrayList<>();
        for (SleepStore.StageSpan span : spans) {
            if (!span.start().isBefore(windowStart) && !span.start().isAfter(windowEnd)) {
                inside.add(span);
            }
        }
        return inside;
    }

    /** 相邻同阶段的分钟合并成一段；分段太多时时间线没法看，也不方便 LLM 读。 */
    private static List<SleepStageSegment> mergeStages(List<SleepStore.StageSpan> spans) {
        List<SleepStageSegment> merged = new ArrayList<>();
        String stage = null;
        LocalDateTime start = null;
        LocalDateTime end = null;
        for (SleepStore.StageSpan span : spans) {
            String name = stageName(span.key());
            if (name == null) {
                continue;
            }
            if (name.equals(stage) && end != null && end.equals(span.start())) {
                end = span.end();
                continue;
            }
            if (stage != null) {
                merged.add(segment(stage, start, end));
            }
            stage = name;
            start = span.start();
            end = span.end();
        }
        if (stage != null) {
            merged.add(segment(stage, start, end));
        }
        return merged;
    }

    private static SleepStageSegment segment(String stage, LocalDateTime start, LocalDateTime end) {
        return new SleepStageSegment(stage, SleepFormat.dateTime(start), SleepFormat.dateTime(end),
                (int) Math.max(0, Duration.between(start, end).toMinutes()));
    }

    /** {@code metric_key} 转成人看的阶段名；午睡等其他 key 返回 {@code null}。 */
    private static String stageName(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case DEEP_KEY -> "deep";
            case LIGHT_KEY -> "light";
            case REM_KEY -> "rem";
            case "PROFESSIONAL_SLEEP_WAKE" -> "wake";
            default -> null;
        };
    }

    /**
     * 各阶段合计。百分比的分母是睡着时长（深 + 浅 + 快动），与报告的「睡眠效率」无关。
     *
     * <p>没有任何分期段时返回 {@code null}：宁可说没有，也不要给一排 0。
     */
    private static SleepStageTotals stageTotals(List<SleepStore.StageSpan> spans, int asleepMinutes) {
        int deep = 0;
        int light = 0;
        int rem = 0;
        int wake = 0;
        boolean found = false;
        for (SleepStore.StageSpan span : spans) {
            String name = stageName(span.key());
            if (name == null) {
                continue;
            }
            found = true;
            int minutes = (int) Math.max(0, Duration.between(span.start(), span.end()).toMinutes());
            switch (name) {
                case "deep" -> deep += minutes;
                case "light" -> light += minutes;
                case "rem" -> rem += minutes;
                default -> wake += minutes;
            }
        }
        if (!found) {
            return null;
        }
        return new SleepStageTotals(deep, light, rem, wake,
                percentOfSleep(deep, asleepMinutes),
                percentOfSleep(light, asleepMinutes),
                percentOfSleep(rem, asleepMinutes));
    }

    private static Double percentOfSleep(int minutes, int asleepMinutes) {
        return asleepMinutes <= 0 ? null : round(minutes * 100d / asleepMinutes, 1);
    }

    /** 只留落在窗口内的碎片，并裁剪到窗口边界（碎片偶尔会比会话多出一两分钟）。 */
    private static List<SleepStore.FragmentSpan> fragmentsWithin(List<SleepStore.FragmentSpan> all,
                                                                LocalDateTime windowStart,
                                                                LocalDateTime windowEnd) {
        if (all == null || all.isEmpty() || windowStart == null || windowEnd == null) {
            return List.of();
        }
        List<SleepStore.FragmentSpan> inside = new ArrayList<>();
        for (SleepStore.FragmentSpan fragment : all) {
            LocalDateTime start = fragment.start().isBefore(windowStart) ? windowStart : fragment.start();
            LocalDateTime end = fragment.end().isAfter(windowEnd) ? windowEnd : fragment.end();
            if (end.isAfter(start)) {
                inside.add(new SleepStore.FragmentSpan(start, end));
            }
        }
        inside.sort(Comparator.comparing(SleepStore.FragmentSpan::start));
        return inside;
    }

    /**
     * 碎片块 + 块间间隙。
     *
     * <p>{@code wakeGapCount} 用 ≥2 分钟的口径，才和 {@code wakeCount}（分期口径）可比；
     * 两者在约 1/3 的夜晚会差 1 次，差的全是 1~2 分钟的空隙，属于口径差异而不是数据错误。
     */
    private static SleepFragments fragmentsFor(List<SleepStore.FragmentSpan> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return new SleepFragments(false, null, null, null, null, List.of(), List.of(), null, null,
                    "该夜没有解码出睡眠碎片（这类数据从 2026-06-12 起才有），相关字段为 null 而不是 0。");
        }
        List<SleepInterval> items = new ArrayList<>(fragments.size());
        int total = 0;
        int longest = 0;
        for (SleepStore.FragmentSpan fragment : fragments) {
            int minutes = (int) Math.round(Duration.between(fragment.start(), fragment.end()).toSeconds() / 60d);
            items.add(new SleepInterval(SleepFormat.dateTime(fragment.start()),
                    SleepFormat.dateTime(fragment.end()), minutes));
            total += minutes;
            longest = Math.max(longest, minutes);
        }

        List<SleepInterval> gaps = new ArrayList<>();
        int gapMinutes = 0;
        int wakeGaps = 0;
        for (int i = 0; i + 1 < fragments.size(); i++) {
            LocalDateTime gapStart = fragments.get(i).end();
            LocalDateTime gapEnd = fragments.get(i + 1).start();
            if (!gapEnd.isAfter(gapStart)) {
                continue;
            }
            int minutes = (int) Math.round(Duration.between(gapStart, gapEnd).toSeconds() / 60d);
            gaps.add(new SleepInterval(SleepFormat.dateTime(gapStart), SleepFormat.dateTime(gapEnd), minutes));
            gapMinutes += minutes;
            if (minutes >= MIN_WAKE_EPISODE_MINUTES) {
                wakeGaps++;
            }
        }
        return new SleepFragments(true, items.size(), total, longest,
                round(total / (double) items.size(), 1), items, gaps, gapMinutes, wakeGaps, null);
    }

    /**
     * 入睡前的白天午睡。
     *
     * <p>三个条件缺一不可：结束时刻早于当晚入睡、距今不超过 24 小时、开始于 08:00~20:00。
     * 最后一条是为了挡掉 {@code PROFESSIONAL_SLEEP_NOON} 里混进来的夜睡段
     * （库里存在 01:00、23:00 起点的 NOON 段，那是夜睡被标错了）。
     */
    private static SleepNap napBefore(List<SleepStore.Span> noons, LocalDateTime windowStart) {
        if (noons == null || noons.isEmpty() || windowStart == null) {
            return null;
        }
        List<SleepInterval> segments = new ArrayList<>();
        int total = 0;
        LocalDate day = null;
        for (SleepStore.Span span : mergeAdjacent(noons)) {
            if (span.end().isAfter(windowStart)) {
                continue;
            }
            if (Duration.between(span.end(), windowStart).toHours() >= 24) {
                continue;
            }
            int hour = span.start().getHour();
            if (hour < 8 || hour >= 20) {
                continue;
            }
            int minutes = (int) Math.round(Duration.between(span.start(), span.end()).toSeconds() / 60d);
            if (minutes <= 0) {
                continue;
            }
            segments.add(new SleepInterval(SleepFormat.dateTime(span.start()),
                    SleepFormat.dateTime(span.end()), minutes));
            total += minutes;
            if (day == null) {
                day = span.start().toLocalDate();
            }
        }
        return segments.isEmpty() ? null : new SleepNap(day, total, segments);
    }

    /**
     * 把首尾相接的段粘起来。
     *
     * <p>分期与午睡都是逐分钟写的，不粘的话一次午睡会输出三十多个「1 分钟」，
     * 既看不出睡了多久，也白白撑大返回体。
     */
    private static List<SleepStore.Span> mergeAdjacent(List<SleepStore.Span> spans) {
        List<SleepStore.Span> merged = new ArrayList<>();
        for (SleepStore.Span span : spans) {
            SleepStore.Span last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && !span.start().isAfter(last.end())) {
                if (span.end().isAfter(last.end())) {
                    merged.set(merged.size() - 1, new SleepStore.Span(last.start(), span.end()));
                }
                continue;
            }
            merged.add(span);
        }
        return merged;
    }

    /** 报告的补充指标；没有报告时返回 {@code null}（整块都没有，而不是一堆 null 字段）。 */
    private static SleepReportExtras reportExtras(SleepStore.ScoredReport report) {
        if (report == null) {
            return null;
        }
        return new SleepReportExtras(
                heartRate(report.avgHeartRate()),
                breathRate(report.avgBreathRate()),
                roundOrNull(report.snoreFreq()),
                epochText(report.bedTimeEpochSeconds()),
                epochText(report.fallAsleepEpochSeconds()),
                epochText(report.risingEpochSeconds()));
    }

    private static Double heartRate(Double value) {
        return value == null || value < 25 || value > 200 ? null : value;
    }

    private static Double breathRate(Double value) {
        return value == null || value < 4 || value > 40 ? null : value;
    }

    private static Double roundOrNull(Double value) {
        return value == null ? null : round(value, 1);
    }

    /** epoch 秒转成 {@code yyyy-MM-dd HH:mm:ss}（本地时间），0 与空当作没记。 */
    private static String epochText(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return SleepFormat.dateTime(java.time.LocalDateTime.ofEpochSecond(epochSeconds, 0, ZONE_OFFSET));
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
     * 按周 / 月的趋势：逐周期的多指标序列 + 主指标的趋势结论。
     *
     * <p>与 {@link #periods(List, String)} 的区别在于「点」的含义：
     * {@code periods} 的 {@code from}/{@code to} 是组内实际有数据的日期范围，
     * 适合回答「这一周覆盖了哪几天」；趋势需要的是<b>周期本身的边界</b>，
     * 才能判断某个周期有没有被查询区间截断（首末各半个周期会把平均值拉偏），
     * 所以这里自己算周期边界，并给出 {@code complete} 与 {@code expectedNights}。
     *
     * <p>趋势只用「完整且该指标有值」的周期做最小二乘，避免被首末截断周期带偏；
     * 完整周期不足 2 个时退回用全部有值的周期，并在 {@code notes} 里说明。
     *
     * @param groupBy {@code week} 或 {@code month}
     * @param metric  主指标，{@code null} 按睡着时长
     */
    public SleepTrendReport trend(DateRange range, String groupBy, SleepTrendMetric metric) {
        SleepTrendMetric target = metric == null ? SleepTrendMetric.ASLEEP : metric;
        List<SleepNight> nights = nights(range);
        SleepCoverage coverage = coverage(nights);
        SleepSummary overall = summarize(nights);
        if (nights.isEmpty()) {
            return new SleepTrendReport(List.of(), null, overall, coverage,
                    List.of("区间 " + range.text() + " 内没有睡眠记录，无法计算趋势。"));
        }

        Map<String, List<SleepNight>> buckets = new LinkedHashMap<>();
        for (SleepNight night : nights) {
            buckets.computeIfAbsent(bucketKey(night.date(), groupBy), key -> new ArrayList<>()).add(night);
        }

        List<SleepTrendPoint> built = new ArrayList<>(buckets.size());
        buckets.forEach((period, group) -> {
            LocalDate[] bounds = periodBounds(group.get(0).date(), groupBy);
            SleepSummary summary = summarize(group);
            built.add(new SleepTrendPoint(
                    period,
                    bounds[0],
                    bounds[1],
                    group.size(),
                    overlapDays(bounds[0], bounds[1], range),
                    !bounds[0].isBefore(range.from()) && !bounds[1].isAfter(range.to()),
                    summary.avgAsleepText(),
                    new SleepTrendValues(
                            summary.avgAsleepMinutes(),
                            summary.avgScore(),
                            summary.avgEfficiencyPercent(),
                            summary.avgHrvMs(),
                            summary.avgWakeCount(),
                            summary.avgDeepPercent(),
                            summary.avgSleepLatencyMinutes()),
                    SleepTrendDeltas.none(),
                    summary.reportNights(),
                    summary.spo2Nights()));
        });
        built.sort(Comparator.comparing(SleepTrendPoint::from));
        List<SleepTrendPoint> points = withDeltas(built);

        SleepTrend trend = trendOf(points, target);
        return new SleepTrendReport(points, trend, overall, coverage,
                trendNotes(points, trend, target));
    }

    /** 周期本身的首尾日期：周取周一~周日，月取 1 号~月末。 */
    private static LocalDate[] periodBounds(LocalDate day, String groupBy) {
        if ("month".equals(groupBy)) {
            LocalDate start = day.withDayOfMonth(1);
            return new LocalDate[] {start, start.withDayOfMonth(start.lengthOfMonth())};
        }
        LocalDate start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new LocalDate[] {start, start.plusDays(6)};
    }

    /** 周期与查询区间重叠的天数。 */
    private static int overlapDays(LocalDate periodStart, LocalDate periodEnd, DateRange range) {
        LocalDate from = periodStart.isBefore(range.from()) ? range.from() : periodStart;
        LocalDate to = periodEnd.isAfter(range.to()) ? range.to() : periodEnd;
        return to.isBefore(from) ? 0 : (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** 给每个点补上「与上一个点」的差值（record 不可变，只能重建一遍）。 */
    private static List<SleepTrendPoint> withDeltas(List<SleepTrendPoint> points) {
        List<SleepTrendPoint> out = new ArrayList<>(points.size());
        SleepTrendValues previous = null;
        for (SleepTrendPoint point : points) {
            out.add(new SleepTrendPoint(point.period(), point.from(), point.to(), point.nights(),
                    point.expectedNights(), point.complete(), point.asleepText(), point.values(),
                    deltas(point.values(), previous), point.reportedNights(), point.spo2Nights()));
            previous = point.values();
        }
        return out;
    }

    private static SleepTrendDeltas deltas(SleepTrendValues current, SleepTrendValues previous) {
        if (current == null || previous == null) {
            return SleepTrendDeltas.none();
        }
        return new SleepTrendDeltas(
                diffInt(current.asleepMinutes(), previous.asleepMinutes()),
                diff(current.score(), previous.score()),
                diff(current.efficiencyPercent(), previous.efficiencyPercent()),
                diff(current.hrvMs(), previous.hrvMs()),
                diff(current.wakeCount(), previous.wakeCount()),
                diff(current.deepPercent(), previous.deepPercent()),
                diff(current.latencyMinutes(), previous.latencyMinutes()));
    }

    private static Integer diffInt(Integer current, Integer previous) {
        return current == null || previous == null ? null : current - previous;
    }

    private static Double diff(Double current, Double previous) {
        return current == null || previous == null ? null : round(current - previous, 1);
    }

    /**
     * 主指标的趋势：先挑参与拟合的周期，再拟合斜率、判方向、找最好最差。
     *
     * <p>排除两类周期：被查询区间截断的（{@code complete=false}），
     * 以及该周期这个指标没有值的（2026-06 之前的评分、几乎全部血氧）。两者都不该算进斜率。
     */
    private static SleepTrend trendOf(List<SleepTrendPoint> points, SleepTrendMetric metric) {
        List<SleepTrendPoint> complete = new ArrayList<>();
        for (SleepTrendPoint point : points) {
            if (point.complete() && metric.valueOf(point.values()) != null) {
                complete.add(point);
            }
        }
        boolean fallback = complete.size() < 2;
        List<SleepTrendPoint> usable = fallback
                ? points.stream().filter(point -> metric.valueOf(point.values()) != null).toList()
                : complete;

        List<SleepTrendValue> values = new ArrayList<>(usable.size());
        for (SleepTrendPoint point : usable) {
            values.add(new SleepTrendValue(point.period(), metric.valueOf(point.values())));
        }
        int excluded = points.size() - values.size();

        if (values.isEmpty()) {
            return new SleepTrend(metric.key(), metric.unit(), metric.higherIsBetter(), 0, points.size(),
                    null, null, null, null, null, null, "unknown", "unknown", null, null,
                    "leastSquaresOverCompletePeriods");
        }
        SleepTrendValue first = values.get(0);
        SleepTrendValue last = values.get(values.size() - 1);
        List<Double> ys = new ArrayList<>(values.size());
        values.forEach(value -> ys.add(value.value()));

        Fit fit = ys.size() < 2 ? null : fit(ys);
        Double slope = fit == null ? null : round(fit.slope(), 2);
        Double r2 = fit == null || fit.r2() == null ? null : round(fit.r2(), 2);
        Double change = ys.size() < 2 ? null : round(last.value() - first.value(), 1);
        Double changePercent = change == null || first.value() == 0d
                ? null : round(change * 100d / Math.abs(first.value()), 1);

        String direction = "unknown";
        String assessment = "unknown";
        if (fit != null) {
            // 用「拟合出来的整个区间累计变化」判定，而不是每周期斜率：
            // 月粒度 5 分钟/月=一年 60 分钟，按每周期比会把一整年的下滑说成平稳。
            double fittedChange = fit.slope() * (ys.size() - 1);
            direction = Math.abs(fittedChange) < metric.minMeaningfulChange()
                    ? "flat" : (fit.slope() > 0 ? "up" : "down");
            assessment = "flat".equals(direction) ? "flat"
                    : ("up".equals(direction) == metric.higherIsBetter() ? "improving" : "worsening");
        }

        SleepTrendValue best = null;
        SleepTrendValue worst = null;
        for (SleepTrendValue value : values) {
            if (best == null || (metric.higherIsBetter()
                    ? value.value() > best.value() : value.value() < best.value())) {
                best = value;
            }
            if (worst == null || (metric.higherIsBetter()
                    ? value.value() < worst.value() : value.value() > worst.value())) {
                worst = value;
            }
        }

        return new SleepTrend(metric.key(), metric.unit(), metric.higherIsBetter(),
                values.size(), excluded, first, last, change, changePercent, slope, r2,
                direction, assessment, best, worst,
                fallback ? "leastSquaresOverAvailablePeriods" : "leastSquaresOverCompletePeriods");
    }

    /** 一元线性拟合的结果；{@code r2} 在因变量完全无波动时为 {@code null}。 */
    private record Fit(double slope, double intercept, Double r2) {
    }

    /** 最小二乘拟合，x 取 0..n-1（周期序号）。 */
    private static Fit fit(List<Double> ys) {
        int n = ys.size();
        double meanX = (n - 1) / 2d;
        double meanY = 0d;
        for (Double y : ys) {
            meanY += y;
        }
        meanY /= n;
        double sxy = 0d;
        double sxx = 0d;
        double syy = 0d;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            double dy = ys.get(i) - meanY;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx == 0d) {
            return new Fit(0d, meanY, null);
        }
        double slope = sxy / sxx;
        // 一元回归的 r² = sxy² / (sxx·syy)；所有 y 相同（syy=0）时线性模型无从解释，给 null
        Double r2 = syy == 0d ? null : Math.min(1d, sxy * sxy / (sxx * syy));
        return new Fit(slope, meanY - slope * meanX, r2);
    }

    private static List<String> trendNotes(List<SleepTrendPoint> points, SleepTrend trend,
                                           SleepTrendMetric metric) {
        if (trend == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>(4);
        if (trend.periodsExcluded() > 0) {
            notes.add("有 " + trend.periodsExcluded() + " 个周期未参与拟合：首/末周期被查询区间截断"
                    + "（complete=false），或这些周期在该指标上没有数据（评分/HRV/潜伏期只有 2026-06-13 之后才有）。");
        }
        if ("unknown".equals(trend.direction())) {
            notes.add("可用于拟合的周期不足 2 个，slopePerPeriod 与 r2 为 null，趋势未判定。");
        } else if ("flat".equals(trend.direction())) {
            notes.add("拟合出的区间累计变化不足 " + plain(metric.minMeaningfulChange()) + " " + metric.unit()
                    + "（该指标的最小有意义变化），判定为平稳。");
        } else if (trend.r2() != null && trend.r2() < 0.3) {
            notes.add("方向判为 " + trend.direction() + "，但 r2=" + trend.r2()
                    + " 偏低：各周期之间的波动大于线性变化，别把这条拟合线当成稳定趋势。");
        }
        long truncated = points.stream().filter(point -> !point.complete()).count();
        if (truncated > 0) {
            notes.add("有 " + truncated + " 个周期被查询区间截断（complete=false）：from/to 仍是周期边界，"
                    + "只统计落在区间内的天数。");
        }
        return notes;
    }

    /** {@code 5.0} 写成 {@code 5}，{@code 0.3} 保持 {@code 0.3}。 */
    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
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
     * 从窗口里挑出清醒段，粘成「醒来」的每一段。
     *
     * <p>贴着头、尾的两段既不算次数也不算清醒时长：会话是从第一段分期（可能就是清醒）开始记录的，
     * 开头那段是躺下没睡着的时间（<b>就是报告的入睡潜伏期</b>），结尾那段是起床前的清醒。
     * 所以这里返回的是 WASO 口径（睡着之后的清醒）。
     */
    private static List<SleepStore.Span> wakeEpisodes(List<SleepStore.Span> spans, LocalDateTime windowStart,
                                                      LocalDateTime windowEnd) {
        if (spans == null || spans.isEmpty() || windowStart == null || windowEnd == null) {
            return List.of();
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
            return List.of();
        }

        // 相邻的清醒段粘成一次醒来
        List<SleepStore.Span> episodes = new ArrayList<>();
        for (SleepStore.Span span : inside) {
            SleepStore.Span last = episodes.isEmpty() ? null : episodes.get(episodes.size() - 1);
            boolean merge = last != null
                    && Duration.between(last.end(), span.start()).toMinutes() <= WAKE_MERGE_GAP_MINUTES;
            if (merge) {
                episodes.set(episodes.size() - 1, new SleepStore.Span(last.start(), span.end()));
            } else {
                episodes.add(span);
            }
        }

        List<SleepStore.Span> result = new ArrayList<>(episodes.size());
        for (int i = 0; i < episodes.size(); i++) {
            SleepStore.Span episode = episodes.get(i);
            boolean touchesStart = i == 0 && !episode.start().isAfter(windowStart);
            boolean touchesEnd = i == episodes.size() - 1 && !episode.end().isBefore(windowEnd);
            if (touchesStart || touchesEnd) {
                continue;
            }
            result.add(episode);
        }
        return result;
    }

    /** 醒来统计：总清醒分钟 + 醒来次数（≥2 分钟才算一次，1 分钟的更像翻身）。 */
    private static WakeStats wakeStats(List<SleepStore.Span> spans, LocalDateTime windowStart,
                                       LocalDateTime windowEnd) {
        List<SleepStore.Span> episodes = wakeEpisodes(spans, windowStart, windowEnd);
        int awakeMinutes = 0;
        int count = 0;
        for (SleepStore.Span episode : episodes) {
            awakeMinutes += episode.minutes();
            if (Duration.between(episode.start(), episode.end()).toMinutes() >= MIN_WAKE_EPISODE_MINUTES) {
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
