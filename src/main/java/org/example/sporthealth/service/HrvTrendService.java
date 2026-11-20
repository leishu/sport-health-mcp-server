package org.example.sporthealth.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.HrvNight;
import org.example.sporthealth.domain.HrvPeriod;
import org.example.sporthealth.domain.HrvTrend;
import org.example.sporthealth.repository.VitalsMetricStore;
import org.example.sporthealth.repository.VitalsMetricStore.SleepWindow;
import org.example.sporthealth.repository.VitalsMetricStore.TimedSample;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.stereotype.Service;

/**
 * 睡眠 HRV（RMSSD）的长期趋势。
 *
 * <p><b>这个服务存在的理由只有一个：别拿全天均值算趋势。</b>
 * 实测全库（9 885 点 / 152 天）日间 HRV 均值 68.7 ms、夜间 48.2 ms，差 20 ms ——
 * 比任何真实变化都大。而全天均值还取决于「那天白天测了几条」：2026-11-10 只有 1 条日间样本，
 * 全天均值 55.0 几乎等于夜间值；2026-11-04 有 26 条日间样本，全天均值被拉到 63.6。
 * 直接对 {@code daily_vitals.hrv_rmssd} 拟合斜率，量到的是「白天测得多不多」，不是恢复能力。
 *
 * <p>所以这里按<b>睡眠会话窗口</b>取均值：一晚的值 = 睡眠期间所有 HRV 样本的均值。
 * 一晚跨越两个日历日（22:12 睡、05:05 起），按 {@code sleep_date}（起床日）归属，
 * 不按样本所在的日历日 —— 按日历日切会把一夜劈成两半，每半还混着别的一夜的碎片。
 *
 * <p>日间均值与全天均值仍然输出，但只作对照（{@code dayRmssd} / {@code wholeDayRmssd}），
 * 不参与趋势判定。
 */
@Service
public class HrvTrendService {

    private static final String METRIC = "HEART_RATE_VARIABILITY";

    /** 一夜至少这么多条 HRV 才算有效：实测每夜 24~49 条，10 条以下基本是半途戴上表的。 */
    private static final int MIN_SAMPLES_PER_NIGHT = 10;

    /** 少于这么多夜不做趋势，单个数字的涨跌说明不了什么。 */
    private static final int MIN_NIGHTS_FOR_TREND = 7;

    /** 区间累计变化小于 4 ms 视为持平；与体征工具里 HRV 的阈值保持一致。 */
    private static final double MIN_MEANINGFUL_CHANGE = 4d;

    /** 默认跨度：90 天，够出 13 个周周期。 */
    private static final int DEFAULT_DAYS = 90;

    /** 明细里最多保留的夜数，超过就只给周期聚合。 */
    private static final int MAX_NIGHT_DETAIL = 120;

    /** 日间窗口：06:00–22:00。 */
    private static final int DAY_START_HOUR = 6;

    private static final int DAY_END_HOUR = 22;

    /** 日间均值至少要这么多条样本才给：单条样本能让均值偏离几十毫秒。 */
    private static final int MIN_DAY_SAMPLES = 5;

    public static final List<String> PERIOD_VALUES = List.of("week", "month");

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final VitalsMetricStore store;

    public HrvTrendService(VitalsMetricStore store) {
        this.store = store;
    }

    public List<String> periods() {
        return PERIOD_VALUES;
    }

    /** 认不出时返回 {@code null}，由工具层报参数错误 —— 不静默退回默认值。 */
    public static String normalizePeriod(String value) {
        if (value == null || value.isBlank()) {
            return "week";
        }
        String text = value.trim().toLowerCase();
        if ("w".equals(text)) {
            return "week";
        }
        if ("m".equals(text)) {
            return "month";
        }
        return PERIOD_VALUES.contains(text) ? text : null;
    }

    /** 有 HRV 数据的最后一天，给默认区间定锚点。 */
    public LocalDate latestDate() {
        return store.latestDate(METRIC).orElse(null);
    }

    public DateRange coveredRange() {
        return store.span(METRIC).orElse(null);
    }

    /**
     * 解析查询区间。
     *
     * <p>锚点用「HRV 数据自己的最后一天」而不是今天 —— HRV 从 2026-06-12 才开始有，
     * 用今天当锚点查默认 90 天没问题，但一旦数据停更，默认区间会整段落空。
     */
    public DateRange resolveRange(String startDate, String endDate, LocalDate anchor) {
        LocalDate lastDay = anchor == null ? LocalDate.now() : anchor;
        LocalDate to = isPresent(endDate) ? DateParser.parseDate(endDate.trim()) : lastDay;
        LocalDate from = isPresent(startDate) ? DateParser.parseDate(startDate.trim())
                : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 晚于结束日期 " + to);
        }
        return new DateRange(from, to);
    }

    /**
     * 区间内的逐夜 HRV。
     *
     * @param range 查询区间（按 {@code sleep_date} 归属，即起床日）
     */
    public List<HrvNight> nights(DateRange range) {
        if (range == null) {
            return List.of();
        }
        // 会话可能在 range 前一天晚上就开始，前后各多读一天，避免首末夜被截断
        DateRange wide = new DateRange(range.from().minusDays(1), range.to().plusDays(1));
        List<TimedSample> samples = store.hrv(wide);
        if (samples.isEmpty()) {
            return List.of();
        }
        List<SleepWindow> windows = store.sleepWindows(range);

        List<HrvNight> out = new ArrayList<>(windows.size());
        for (SleepWindow window : windows) {
            List<TimedSample> inSleep = new ArrayList<>();
            List<TimedSample> inDay = new ArrayList<>();
            List<TimedSample> wholeDay = new ArrayList<>();
            for (TimedSample sample : samples) {
                if (!sample.at().isBefore(window.start()) && !sample.at().isAfter(window.end())) {
                    inSleep.add(sample);
                }
                if (sample.at().toLocalDate().equals(window.sleepDate())) {
                    wholeDay.add(sample);
                    int hour = sample.at().getHour();
                    if (hour >= DAY_START_HOUR && hour < DAY_END_HOUR) {
                        inDay.add(sample);
                    }
                }
            }
            if (inSleep.isEmpty()) {
                // 这一夜设备没测 HRV（换设备前根本没这个指标），留空行没有意义，直接跳过
                continue;
            }
            // 日间均值只在样本够时给出：实测 2026-11-10 白天只有 1 条 126 ms 的样本，
            // 直接平均会让那一周出现一个 100+ 的假高点，被读成「HRV 突然变好」。
            Double dayValue = inDay.size() >= MIN_DAY_SAMPLES ? mean(inDay) : null;
            out.add(new HrvNight(
                    window.sleepDate(),
                    clock(window.start()),
                    clock(window.end()),
                    inSleep.size(),
                    round(mean(inSleep), 1),
                    inDay.size(),
                    round(dayValue, 1),
                    round(mean(wholeDay), 1)));
        }
        return out;
    }

    /**
     * 按周或月聚合并判趋势。
     *
     * @param range  查询区间
     * @param period {@code week} 或 {@code month}
     */
    public HrvTrend trend(DateRange range, String period) {
        List<HrvNight> nights = nights(range);
        int excludedNights = (int) nights.stream()
                .filter(night -> !night.reliable(MIN_SAMPLES_PER_NIGHT))
                .count();
        List<HrvNight> usable = nights.stream()
                .filter(night -> night.reliable(MIN_SAMPLES_PER_NIGHT))
                .toList();
        return summarize(aggregate(usable, range, period), nights.size(), usable,
                excludedNights, range);
    }

    /** 区间内参与统计的夜数上限提醒，给工具层判断是否要截断明细。 */
    public static int maxNightDetail() {
        return MAX_NIGHT_DETAIL;
    }

    /** 把每夜的值按周期聚合。周期本身的首尾从日历推，不从数据推 —— 否则判断不了截断。 */
    private static List<HrvPeriod> aggregate(List<HrvNight> nights, DateRange range, String period) {
        Map<String, List<HrvNight>> grouped = new LinkedHashMap<>();
        for (HrvNight night : nights) {
            grouped.computeIfAbsent(bucketKey(night.date(), period), key -> new ArrayList<>()).add(night);
        }
        List<HrvPeriod> out = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<HrvNight>> entry : grouped.entrySet()) {
            List<HrvNight> group = entry.getValue();
            LocalDate[] bounds = periodBounds(group.get(0).date(), period);
            LocalDate from = bounds[0].isBefore(range.from()) ? range.from() : bounds[0];
            LocalDate to = bounds[1].isAfter(range.to()) ? range.to() : bounds[1];
            boolean complete = !bounds[0].isBefore(range.from()) && !bounds[1].isAfter(range.to());
            List<Double> values = group.stream().map(HrvNight::rmssd).filter(v -> v != null).toList();
            List<Double> days = group.stream().map(HrvNight::dayRmssd).filter(v -> v != null).toList();
            out.add(new HrvPeriod(
                    entry.getKey(),
                    from,
                    to,
                    complete,
                    group.size(),
                    round(meanOf(values), 1),
                    round(minOf(values), 1),
                    round(maxOf(values), 1),
                    round(meanOf(days), 1)));
        }
        out.sort(Comparator.comparing(HrvPeriod::from));
        return out;
    }

    /**
     * 拟合与判定。
     *
     * <p>只用完整周期：首末两个周期被查询区间截断，天数和夜数都比别人少，
     * 拉进斜率里会让趋势偏向「首周期看起来更低」这个纯几何效应。
     * 完整周期不足 2 个时退回全部可用周期，并在 notes 里说明。
     */
    private static HrvTrend summarize(List<HrvPeriod> periods, int totalNights,
                                      List<HrvNight> usable, int excludedNights, DateRange range) {
        List<HrvPeriod> complete = periods.stream()
                .filter(HrvPeriod::complete)
                .filter(point -> point.rmssd() != null)
                .toList();
        boolean fallback = complete.size() < 2;
        List<HrvPeriod> used = fallback
                ? periods.stream().filter(point -> point.rmssd() != null).toList()
                : complete;

        List<HrvNight> all = usable;
        List<Double> values = all.stream().map(HrvNight::rmssd).toList();
        HrvPeriod latest = periods.stream()
                .filter(point -> point.rmssd() != null)
                .reduce((a, b) -> b)
                .orElse(null);

        Double average = round(meanOf(values), 1);
        Double min = round(minOf(values), 1);
        Double max = round(maxOf(values), 1);

        Double change = null;
        Double changePercent = null;
        Double slope = null;
        Double r2 = null;
        String direction = "unknown";
        String assessment = "unknown";

        if (used.size() >= 2) {
            List<Double> ys = used.stream().map(HrvPeriod::rmssd).toList();
            Fit fit = fit(ys);
            slope = round(fit.slope(), 2);
            r2 = fit.r2() == null ? null : round(fit.r2(), 2);
            HrvPeriod first = used.get(0);
            HrvPeriod last = used.get(used.size() - 1);
            change = round(last.rmssd() - first.rmssd(), 1);
            changePercent = first.rmssd() == null || first.rmssd() == 0d
                    ? null : round(change * 100d / Math.abs(first.rmssd()), 1);
            // 用「整段拟合累计变化」判方向，不按每周期斜率：
            // 周粒度 0.5 ms/周 = 一年 26 ms，按每周期比会被 4 ms 阈值判成 flat
            double fittedChange = fit.slope() * (ys.size() - 1);
            direction = Math.abs(fittedChange) < MIN_MEANINGFUL_CHANGE
                    ? "flat" : (fit.slope() > 0 ? "up" : "down");
            assessment = "flat".equals(direction) ? "flat"
                    : ("up".equals(direction) ? "improving" : "worsening");
        }

        List<String> notes = notes(periods, totalNights, usable.size(), excludedNights,
                used.size(), direction, r2, fallback, range);

        return new HrvTrend(
                "ms",
                true,
                "sleep",
                periods,
                all.size(),
                average,
                min,
                max,
                latest,
                change,
                changePercent,
                slope,
                r2,
                direction,
                assessment,
                used.size(),
                periods.size() - used.size(),
                excludedNights,
                notes);
    }

    private static List<String> notes(List<HrvPeriod> periods, int totalNights, int usableNights,
                                      int excludedNights, int periodsUsed, String direction, Double r2,
                                      boolean fallback, DateRange range) {
        List<String> notes = new ArrayList<>();
        if (totalNights == 0) {
            notes.add("区间内没有落在睡眠时段里的 HRV 采样。HRV 从 2026-06-12 才有数据，"
                    + "更早的日期只能看静息心率。");
            return notes;
        }
        notes.add("口径是「睡眠 HRV」：每晚取睡眠会话窗口内所有样本的均值，按起床日归属。"
                + "同一日期在 get_daily_vitals 里的 hrv 是日历日全天均值，"
                + "两者数值会不同（日间样本多的时候全天均值明显偏高），不要拿它们互相校验。");
        if (excludedNights > 0) {
            notes.add("有 " + excludedNights + " 夜样本不足 " + MIN_SAMPLES_PER_NIGHT
                    + " 条，已排除；区间内共 " + totalNights + " 夜有 HRV，其中 "
                    + usableNights + " 夜参与统计。");
        }
        if (range.dayCount() < MIN_NIGHTS_FOR_TREND) {
            notes.add("区间只有 " + range.dayCount() + " 天，样本太少，不做趋势判定。");
        }
        if (fallback && periods.size() >= 2) {
            notes.add("完整周期不足 2 个（首末周期被查询区间截断），已改用全部可用周期拟合，"
                    + "结论仅供参考；把 startDate/endDate 落到周期边界上会更准。");
        }
        if (direction == null || "unknown".equals(direction)) {
            notes.add("可用于拟合的周期不足 2 个，slopePerPeriod 与 r2 为 null，趋势未判定。");
        } else if (periodsUsed == 2) {
            // 两点定一条直线，r2 必然是 1.0。不说清的话，「r2=1.0」会被当成趋势极稳的证据。
            notes.add("参与拟合的周期只有 2 个，r2 必然接近 1（两点定一条直线），"
                    + "不能当作趋势稳定的证据。把区间拉长或换成周粒度，结论才有说服力。");
        } else if (r2 != null && r2 < 0.3) {
            notes.add("方向判为 " + direction + "，但 r2=" + r2 + " —— 波动大于趋势，"
                    + "单看首末差值容易高估变化，建议按月粒度看更长时间。");
        }
        notes.add("HRV 受测量时段、睡前饮酒、睡眠时长、训练负荷影响很大，单夜的数字没有意义，"
                + "至少看一周的均值。");
        return notes;
    }

    /** 周取周一~周日，月取 1 号~月末。 */
    private static LocalDate[] periodBounds(LocalDate day, String period) {
        if ("month".equals(period)) {
            LocalDate start = day.withDayOfMonth(1);
            return new LocalDate[] {start, start.withDayOfMonth(start.lengthOfMonth())};
        }
        LocalDate start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new LocalDate[] {start, start.plusDays(6)};
    }

    private static String bucketKey(LocalDate day, String period) {
        if ("month".equals(period)) {
            return MONTH_KEY.format(YearMonth.from(day));
        }
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return String.format("%d-W%02d",
                monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** 一元线性拟合的结果；{@code r2} 在因变量完全无波动时为 {@code null}。 */
    private record Fit(double slope, Double r2) {
    }

    private static Fit fit(List<Double> ys) {
        int n = ys.size();
        double meanX = (n - 1) / 2d;
        double meanY = meanOf(ys);
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
            return new Fit(0d, null);
        }
        return new Fit(sxy / sxx, syy == 0d ? null : Math.min(1d, sxy * sxy / (sxx * syy)));
    }

    private static Double mean(List<TimedSample> samples) {
        return samples.isEmpty() ? null
                : samples.stream().mapToDouble(TimedSample::value).average().orElse(Double.NaN);
    }

    private static Double meanOf(List<Double> values) {
        return values.isEmpty() ? null
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static Double minOf(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    private static Double maxOf(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static Double round(Double value, int scale) {
        return value == null ? null : PaceFormat.round(value, scale);
    }

    private static String clock(LocalDateTime at) {
        return at == null ? null : String.format("%02d:%02d", at.getHour(), at.getMinute());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
