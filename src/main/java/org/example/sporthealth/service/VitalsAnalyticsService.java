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
import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.HeartRateStat;
import org.example.sporthealth.domain.VitalsAvailability;
import org.example.sporthealth.domain.VitalsCoverage;
import org.example.sporthealth.domain.VitalsMetricStat;
import org.example.sporthealth.domain.VitalsPeriod;
import org.example.sporthealth.repository.VitalsStore;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.stereotype.Service;

/**
 * 体征分析：把 {@code daily_vitals} 的一天一行拼成区间统计、周期聚合与变化方向。
 *
 * <p><b>四类指标的可用起点不同，所以所有统计都按指标各自算分母</b>：
 * 静息心率 2025-11-08 起、HRV 与压力 2026-06-12 起、VO2max 只有 104 天且中间断档 14 个月。
 * 「区间均值」只在有值的那几天上算，绝不把 null 当 0 拉低平均。
 *
 * <p><b>变化方向用「末 7 天均值 − 首 7 天均值」，不用最小二乘斜率</b>：
 * 静息心率逐日噪声就有 2~3 bpm，日粒度做线性回归的 r² 常年在 0.1 以下，
 * 拟合出来的斜率正负号基本由首末几天的睡眠质量决定。首末两个 7 天窗口更稳，
 * 也更容易解释给用户。区间不足 {@value #MIN_DAYS_FOR_TREND} 天就直接返回 {@code unknown}
 * —— 那是「看不出」，不是「没变化」。
 *
 * <p><b>三个对比量各管一件事</b>（见 {@link VitalsMetricStat}）：
 * {@code baseline} 是区间末端往前 28 天的均值（不受区间限制），回答「相对最近一个月如何」；
 * {@code deltaVsBaseline} 是最近 7 天相对它的偏离；{@code change} 是区间内部的首末对比。
 */
@Service
@Slf4j
public class VitalsAnalyticsService {

    /** 不传日期时的默认窗口：最近 30 天。体征是日粒度快照，30 天足够看出恢复状态。 */
    private static final int DEFAULT_DAYS = 30;

    /** 基线窗口：区间末端往前 28 天（含末端）。四周是运动生理里判断恢复的常用窗口。 */
    private static final int BASELINE_DAYS = 28;

    /** 首末对比窗口：各取 7 天。 */
    private static final int TREND_WINDOW_DAYS = 7;

    /** 少于这么多天不做首末对比，否则两个窗口会重叠。 */
    private static final int MIN_DAYS_FOR_TREND = 14;

    /** 少于这么多「有值的天」不做首末对比，一天的值撑不起一个均值。 */
    private static final int MIN_COUNT_FOR_TREND = 7;

    /**
     * 各指标判定「有变化」的阈值。
     *
     * <p>按各自噪声水平定：静息心率日波动 2~3 bpm，阈值取 2；
     * HRV 个体内日变异约 10%（这里约 5 ms），取 4 ms；压力取 2 分；VO2max 设备精度约 ±1，取 1。
     */
    private static final double RHR_THRESHOLD = 2.0;
    private static final double HRV_THRESHOLD = 4.0;
    private static final double STRESS_THRESHOLD = 2.0;
    private static final double VO2MAX_THRESHOLD = 1.0;
    private static final double HEART_RATE_THRESHOLD = 2.0;

    /**
     * 华为压力分级的下界：0~29 放松、30~59 正常、60~79 中等、80~100 高。
     *
     * <p>只用于给区间均值一句话的定性，不参与任何计算。
     */
    private static final double[] STRESS_BANDS = {30, 60, 80};
    private static final String[] STRESS_BAND_NAMES = {"放松", "正常", "中等", "高"};

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private final VitalsStore store;
    private final Clock clock;

    public VitalsAnalyticsService(VitalsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 解析查询区间。
     *
     * <p>不传时取<b>数据最新一天往前 30 天</b>，而不是「今天往前 30 天」：
     * 库通常落后于当天，用今天会把尾巴留成一片空白，看起来像「最近没戴表」。
     * 用户显式传日期时不做任何兜底，按原样解释。
     */
    public DateRange resolveRange(String startDate, String endDate) {
        Optional<DateRange> covered = store.coveredRange();
        LocalDate lastDay = covered.map(DateRange::to).orElse(LocalDate.now(clock));
        LocalDate firstDay = covered.map(DateRange::from).orElse(lastDay.minusDays(DEFAULT_DAYS - 1L));

        LocalDate to = isPresent(endDate) ? DateParser.parseDate(endDate.trim())
                : lastDay;
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

    /** 区间内的逐日体征，按日期升序。 */
    public List<DailyVitals> days(DateRange range) {
        return store.days(range);
    }

    /** 五个指标在全库的可用范围，用来解释「这段区间为什么没有某个指标」。 */
    public List<VitalsAvailability> availability() {
        return store.availability();
    }

    /**
     * 覆盖计数：分母是区间自然日数，每个指标各自数自己有值的天数。
     *
     * <p>{@code dailyRows} 是「表里有行」的天数，会比 {@code restingHeartRateDays} 大 ——
     * 2024-07~2025-11 那 100 行只有 VO2max，没有心率。
     */
    public VitalsCoverage coverage(DateRange range, List<DailyVitals> days) {
        int rhr = 0;
        int hrv = 0;
        int stress = 0;
        int vo2 = 0;
        int heartRate = 0;
        for (DailyVitals day : days) {
            if (day.restingHeartRate() != null) {
                rhr++;
            }
            if (day.hrvRmssd() != null) {
                hrv++;
            }
            if (day.stressAverage() != null) {
                stress++;
            }
            if (day.vo2max() != null) {
                vo2++;
            }
            if (day.sampledHeartRate()) {
                heartRate++;
            }
        }
        return new VitalsCoverage(range.dayCount(), days.size(), rhr, hrv, stress, vo2, heartRate);
    }

    /**
     * 四个单值指标的统计；区间内完全没有值的指标<b>不出现在结果里</b>。
     *
     * <p>不输出一个全 null 的对象：那会让调用方分不清「没有这个指标」和「有但都是 null」，
     * 覆盖情况交给 {@link VitalsCoverage} 与 {@link VitalsAvailability} 说明。
     *
     * @return 键为 {@code restingHeartRate} / {@code hrv} / {@code stress} / {@code vo2max}
     */
    public Map<String, VitalsMetricStat> metrics(DateRange range, List<DailyVitals> days) {
        VitalsStore.Baselines baseline = store.baselines(
                new DateRange(range.to().minusDays(BASELINE_DAYS - 1L), range.to()));

        Map<String, VitalsMetricStat> out = new LinkedHashMap<>();
        putStat(out, "restingHeartRate", days,
                d -> d.restingHeartRate() == null ? null : d.restingHeartRate().doubleValue(),
                "bpm", false, RHR_THRESHOLD, baseline.restingHeartRate(), 0);
        putStat(out, "hrv", days, DailyVitals::hrvRmssd,
                "ms", true, HRV_THRESHOLD, baseline.hrv(), 1);
        putStat(out, "stress", days, DailyVitals::stressAverage,
                "score", false, STRESS_THRESHOLD, baseline.stress(), 1);
        putStat(out, "vo2max", days, DailyVitals::vo2max,
                "ml/kg/min", true, VO2MAX_THRESHOLD, baseline.vo2max(), 1);
        return out;
    }

    /**
     * 全天自动心率的概览；区间内一天采样都没有时返回 null。
     *
     * <p>注意 {@code lowest} / {@code highest} 是采样极值，不是统计日值的最小/最大，
     * 所以不能塞进 {@link VitalsMetricStat}。
     */
    public HeartRateStat heartRate(List<DailyVitals> days) {
        List<DailyVitals> sampled = days.stream().filter(DailyVitals::sampledHeartRate).toList();
        if (sampled.isEmpty()) {
            return null;
        }
        List<DailyVitals> withAverage = sampled.stream().filter(d -> d.averageHeartRate() != null).toList();
        double average = withAverage.stream().mapToDouble(DailyVitals::averageHeartRate).average().orElse(Double.NaN);
        Integer lowest = sampled.stream().map(DailyVitals::minHeartRate).filter(v -> v != null)
                .min(Integer::compareTo).orElse(null);
        Integer highest = sampled.stream().map(DailyVitals::maxHeartRate).filter(v -> v != null)
                .max(Integer::compareTo).orElse(null);
        DailyVitals latest = withAverage.isEmpty() ? null : withAverage.get(withAverage.size() - 1);

        Double change = windowChange(withAverage, DailyVitals::averageHeartRate);
        String direction = direction(change, HEART_RATE_THRESHOLD);
        return new HeartRateStat(
                "bpm",
                sampled.size(),
                round(average, 1),
                lowest,
                highest,
                latest == null ? null : round(latest.averageHeartRate(), 1),
                latest == null ? null : latest.date(),
                change,
                direction,
                assessment(direction, false));
    }

    /**
     * 按周或月聚合。
     *
     * <p>周期边界自己算（周 = 周一到周日，月 = 1 号到月末），不用「组内实际日期范围」——
     * 那样无法判断首末周期是不是被查询区间截断，{@code complete} 就会失真。
     *
     * <p>没有数据的周期不输出，但每个周期都带真实的 {@code from}/{@code to}，
     * 断档（比如 VO2max 那 14 个月）在返回值里表现为相邻周期日期跳开，不会被当成连续。
     */
    public List<VitalsPeriod> periods(List<DailyVitals> days, DateRange range, String groupBy) {
        Map<String, List<DailyVitals>> buckets = new LinkedHashMap<>();
        for (DailyVitals day : days) {
            buckets.computeIfAbsent(periodKey(day.date(), groupBy), k -> new ArrayList<>()).add(day);
        }
        List<VitalsPeriod> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, List<DailyVitals>> entry : buckets.entrySet()) {
            List<DailyVitals> bucket = entry.getValue();
            LocalDate bucketFrom = periodStart(bucket.get(0).date(), groupBy);
            LocalDate bucketTo = periodEnd(bucket.get(0).date(), groupBy);
            boolean complete = !bucketFrom.isBefore(range.from()) && !bucketTo.isAfter(range.to());
            out.add(new VitalsPeriod(
                    entry.getKey(),
                    bucketFrom,
                    bucketTo,
                    complete,
                    bucket.size(),
                    intOrNull(mean(bucket, d -> d.restingHeartRate() == null ? null : d.restingHeartRate().doubleValue())),
                    round(mean(bucket, DailyVitals::hrvRmssd), 1),
                    round(mean(bucket, DailyVitals::stressAverage), 1),
                    bucket.stream().map(DailyVitals::stressMax).filter(v -> v != null)
                            .max(Integer::compareTo).orElse(null),
                    round(mean(bucket, DailyVitals::vo2max), 1),
                    round(mean(bucket, DailyVitals::averageHeartRate), 1),
                    bucket.stream().map(DailyVitals::minHeartRate).filter(v -> v != null)
                            .min(Integer::compareTo).orElse(null),
                    bucket.stream().map(DailyVitals::maxHeartRate).filter(v -> v != null)
                            .max(Integer::compareTo).orElse(null)));
        }
        return out;
    }

    /**
     * 给调用方看的读法提示。
     *
     * <p>只在真出现这类情况时才说，不制造噪音：指标整段缺失、VO2max 断档、
     * 静息心率明显高于基线、HRV 明显低于基线、压力落在哪一档。
     */
    public List<String> notes(DateRange range, VitalsCoverage coverage,
                              Map<String, VitalsMetricStat> metrics,
                              List<VitalsAvailability> availability) {
        List<String> notes = new ArrayList<>();
        Map<String, VitalsAvailability> spans = new LinkedHashMap<>();
        availability.forEach(a -> spans.put(a.metric(), a));

        addMissingNote(notes, "静息心率", "restingHeartRate", coverage.restingHeartRateDays(), spans);
        addMissingNote(notes, "HRV", "hrv", coverage.hrvDays(), spans);
        addMissingNote(notes, "压力", "stress", coverage.stressDays(), spans);
        addMissingNote(notes, "最大摄氧量", "vo2max", coverage.vo2maxDays(), spans);

        VitalsAvailability vo2 = spans.get("vo2max");
        if (vo2 != null && coverage.vo2maxDays() > 1 && vo2.days() < vo2.spanDays() / 2) {
            notes.add("VO2max 在全库里只有 " + vo2.days() + " 天有值（跨 " + vo2.spanDays()
                    + " 天），中间有长达数月的断档：首末值不能直接读成「训练一年提升了多少」，"
                    + "这段变化里混着设备停止上报的空窗。");
        }

        VitalsMetricStat rhr = metrics.get("restingHeartRate");
        if (rhr != null && rhr.deltaVsBaseline() != null && rhr.deltaVsBaseline() >= 5) {
            notes.add("最近 7 天静息心率比 28 天基线高 " + fmt(rhr.deltaVsBaseline())
                    + " bpm，超过 5 bpm 的常见预警线，值得结合训练量与睡眠一起看。");
        }
        VitalsMetricStat hrv = metrics.get("hrv");
        if (hrv != null && hrv.baseline() != null && hrv.baseline() > 0 && hrv.deltaVsBaseline() != null
                && hrv.deltaVsBaseline() <= -0.1 * hrv.baseline()) {
            notes.add("最近 7 天 HRV 比 28 天基线低 " + fmt(Math.abs(hrv.deltaVsBaseline()) / hrv.baseline() * 100)
                    + "%，超过 10% 的常见恢复不足阈值。");
        }
        VitalsMetricStat stress = metrics.get("stress");
        if (stress != null && stress.average() != null) {
            notes.add("区间平均压力 " + fmt(stress.average()) + "，按华为分级属于「" + stressBand(stress.average())
                    + "」（0~29 放松 / 30~59 正常 / 60~79 中等 / 80~100 高）。");
        }
        List<String> noTrend = metrics.entrySet().stream()
                .filter(e -> "unknown".equals(e.getValue().direction()))
                .map(Map.Entry::getKey)
                .toList();
        if (!noTrend.isEmpty()) {
            notes.add("以下指标在区间内的有值跨度不足 " + MIN_DAYS_FOR_TREND + " 天（或有值天数不足 "
                    + MIN_COUNT_FOR_TREND + " 天），不做首末对比：" + String.join("、", noTrend)
                    + "；direction=unknown 是「看不出变化」，不是「没变化」。");
        }
        notes.add("heartRate.average 是全天自动采样的均值（含睡眠段），比「日间平均心率」低约 10 bpm；"
                + "heartRate.highest 也只是采样峰值，不能当最大心率用。");
        return notes;
    }

    /** 指标整段缺失时补一句解释，避免被读成「数据质量差」。 */
    private static void addMissingNote(List<String> notes, String label, String key, int count,
                                       Map<String, VitalsAvailability> spans) {
        if (count > 0) {
            return;
        }
        VitalsAvailability span = spans.get(key);
        if (span == null) {
            notes.add("区间内没有" + label + "记录，且库里从未出现过该指标。");
        } else {
            notes.add("区间内没有" + label + "记录；该指标全库只有 " + span.from() + " ~ " + span.to()
                    + " 的 " + span.days() + " 天有值。");
        }
    }

    // ---------------------------------------------------------------- 统计小工具

    /**
     * 一个单值指标的统计。
     *
     * <p>首末窗口锚在<b>该指标自己</b>的第一个/最后一个有值日，不是区间端点：
     * 查一年时 HRV 只有后半年的数据，用区间首端取窗口会全空，趋势就永远是 unknown
     * —— 那不是「看不出变化」，是「窗口取错了位置」。
     *
     * @param roundTo 平均值保留几位小数；0 表示取整（静息心率）
     */
    private static void putStat(Map<String, VitalsMetricStat> out, String key,
                                List<DailyVitals> days, Function<DailyVitals, Double> extractor,
                                String unit, boolean higherIsBetter, double threshold,
                                Double baseline, int roundTo) {
        List<DailyVitals> withValue = days.stream().filter(d -> extractor.apply(d) != null).toList();
        if (withValue.isEmpty()) {
            return;
        }
        double average = withValue.stream().mapToDouble(extractor::apply).average().orElse(Double.NaN);
        double min = withValue.stream().mapToDouble(extractor::apply).min().orElse(Double.NaN);
        double max = withValue.stream().mapToDouble(extractor::apply).max().orElse(Double.NaN);
        DailyVitals latest = withValue.get(withValue.size() - 1);

        LocalDate firstDate = withValue.get(0).date();
        LocalDate lastDate = latest.date();
        boolean enough = coversEnoughForTrend(firstDate, lastDate, withValue.size());
        Double firstWindow = windowMean(withValue, extractor, firstDate, true);
        Double lastWindow = windowMean(withValue, extractor, lastDate, false);
        Double change = (enough && firstWindow != null && lastWindow != null)
                ? round(lastWindow - firstWindow, 1) : null;
        String direction = direction(change, threshold);

        out.put(key, new VitalsMetricStat(
                unit,
                higherIsBetter,
                withValue.size(),
                round(average, roundTo),
                round(min, roundTo),
                round(max, roundTo),
                round(extractor.apply(latest), roundTo),
                lastDate,
                round(baseline, roundTo),
                (enough && lastWindow != null && baseline != null)
                        ? round(lastWindow - baseline, 1) : null,
                change,
                direction,
                assessment(direction, higherIsBetter)));
    }

    /**
     * 首末两个 7 天窗口能不能撑起一次对比。
     *
     * <p>要求有值跨度 ≥14 天（否则两个窗口重叠）且总天数 ≥7（一天的值撑不起一个均值）。
     */
    private static boolean coversEnoughForTrend(LocalDate firstDate, LocalDate lastDate, int count) {
        long span = lastDate.toEpochDay() - firstDate.toEpochDay() + 1;
        return span >= MIN_DAYS_FOR_TREND && count >= MIN_COUNT_FOR_TREND;
    }

    /**
     * 以某个日期为端点、向区间内侧取 7 天的窗口均值。
     *
     * @param anchor 锚点：{@code head=true} 时是窗口起点，否则是窗口终点
     */
    private static Double windowMean(List<DailyVitals> withValue, Function<DailyVitals, Double> extractor,
                                     LocalDate anchor, boolean head) {
        LocalDate from = head ? anchor : anchor.minusDays(TREND_WINDOW_DAYS - 1L);
        LocalDate to = head ? anchor.plusDays(TREND_WINDOW_DAYS - 1L) : anchor;
        List<Double> values = withValue.stream()
                .filter(d -> !d.date().isBefore(from) && !d.date().isAfter(to))
                .map(extractor)
                .filter(v -> v != null)
                .toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** 通用的首末窗口变化量，给 {@link HeartRateStat} 用。窗口锚在该指标自己的首末有值日。 */
    private static Double windowChange(List<DailyVitals> withValue, Function<DailyVitals, Double> extractor) {
        if (withValue.isEmpty()) {
            return null;
        }
        LocalDate firstDate = withValue.get(0).date();
        LocalDate lastDate = withValue.get(withValue.size() - 1).date();
        if (!coversEnoughForTrend(firstDate, lastDate, withValue.size())) {
            return null;
        }
        Double head = windowMean(withValue, extractor, firstDate, true);
        Double tail = windowMean(withValue, extractor, lastDate, false);
        return head == null || tail == null ? null : round(tail - head, 1);
    }

    private static Double mean(List<DailyVitals> days, Function<DailyVitals, Double> extractor) {
        List<Double> values = days.stream().map(extractor).filter(v -> v != null).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * 数值方向。
     *
     * <p>{@code change} 为 null 表示「无从判断」，返回 {@code unknown} 而不是 {@code flat}
     * —— 把「数据不够」说成「没变化」是最容易误导人的一种偷懒。
     */
    private static String direction(Double change, double threshold) {
        if (change == null) {
            return "unknown";
        }
        if (change > threshold) {
            return "up";
        }
        if (change < -threshold) {
            return "down";
        }
        return "flat";
    }

    /** 价值方向：只看数值涨跌会把「静息心率上升」读成好事。 */
    private static String assessment(String direction, boolean higherIsBetter) {
        return switch (direction) {
            case "up" -> higherIsBetter ? "improving" : "worsening";
            case "down" -> higherIsBetter ? "worsening" : "improving";
            case "flat" -> "flat";
            default -> "unknown";
        };
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

    /** 华为压力分级的名字，只用于一句话定性。 */
    private static String stressBand(double average) {
        for (int i = 0; i < STRESS_BANDS.length; i++) {
            if (average < STRESS_BANDS[i]) {
                return STRESS_BAND_NAMES[i];
            }
        }
        return STRESS_BAND_NAMES[STRESS_BAND_NAMES.length - 1];
    }

    private static Double round(Double value, int digits) {
        if (value == null || value.isNaN()) {
            return null;
        }
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static Integer intOrNull(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
