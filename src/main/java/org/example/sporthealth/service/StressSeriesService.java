package org.example.sporthealth.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.StressBucket;
import org.example.sporthealth.domain.StressDay;
import org.example.sporthealth.domain.VitalsSample;
import org.example.sporthealth.repository.VitalsMetricStore;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.stereotype.Service;

/**
 * 单日压力曲线（{@code health_metric} 的 {@code STRESS}）。
 *
 * <p>三个必须写进返回体的事实（全库实测 4 837 点 / 152 天）：
 * <ul>
 *   <li><b>约 30 分钟一个点</b>，不是连续曲线，所以桶最小给到 30 分钟；</li>
 *   <li><b>最低分恒为 15</b>，19.4% 的样本正好是 15 —— 这是算法下限，不是「压力为零」。
 *       真实值域只有 15~53，从没到过 60，所以「高压」档在这份数据里永远不会出现；</li>
 *   <li><b>夜间也在测，而且夜间均值更高</b>（22–05 时 27~30 分，白天 10–17 时 20~23 分）。
 *       这与「睡觉时压力该低」的直觉相反，是设备测的是夜间静息时的自主神经张力，
 *       不是主观紧张度。所以别拿夜间高分去推断睡眠质量差。</li>
 * </ul>
 */
@Service
public class StressSeriesService {

    private static final String METRIC = "STRESS";

    /** 每小时 60 分钟；默认桶。 */
    private static final int DEFAULT_BUCKET_MINUTES = 60;

    /** 数据本身的采样间隔就是 30 分钟，再细分没有意义。 */
    private static final int MIN_BUCKET_MINUTES = 30;

    /** 分档边界，华为口径 0~100：0–29 放松、30–59 正常、60–79 中等、80–100 高压。 */
    private static final int RELAXED_UPPER = 30;
    private static final int NORMAL_UPPER = 60;
    private static final int MODERATE_UPPER = 80;

    /** 算法下限：值等于它时是「低到顶了」，不是精确测量。 */
    private static final int FLOOR_SCORE = 15;

    /** 末次测量早于这个时刻，说明当天没覆盖到傍晚，「全天均值」名不副实。 */
    private static final int LATE_HOUR = 20;

    public static final List<String> RESOLUTION_VALUES = List.of("30min", "hour");

    private final VitalsMetricStore store;

    public StressSeriesService(VitalsMetricStore store) {
        this.store = store;
    }

    public List<String> resolutions() {
        return RESOLUTION_VALUES;
    }

    /** 认不出时返回 {@code null}，由工具层报参数错误。 */
    public static Integer normalizeResolution(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BUCKET_MINUTES;
        }
        return switch (value.trim().toLowerCase()) {
            case "30min", "30", "half" -> 30;
            case "hour", "60min", "60" -> 60;
            default -> null;
        };
    }

    public LocalDate latestDate() {
        return store.latestDate(METRIC).orElse(null);
    }

    public DateRange coveredRange() {
        return store.span(METRIC).orElse(null);
    }

    /** 单日压力；这天没有采样时返回 {@code null}。 */
    public StressDay day(LocalDate date, int bucketMinutes) {
        if (date == null) {
            return null;
        }
        List<VitalsSample> samples = store.stress(new DateRange(date, date));
        if (samples.isEmpty()) {
            return null;
        }
        int bucket = Math.max(MIN_BUCKET_MINUTES, bucketMinutes);

        List<Double> values = samples.stream().map(VitalsSample::value).toList();
        double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        int min = (int) Math.round(values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN));
        int max = (int) Math.round(values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));

        return new StressDay(
                date,
                samples.size(),
                min,
                PaceFormat.round(average, 1),
                max,
                level(average),
                samples.get(0).time(),
                samples.get(samples.size() - 1).time(),
                medianGap(samples),
                buckets(samples, bucket),
                levels(samples),
                peak(samples, true),
                peak(samples, false));
    }

    /** 按桶聚合；桶里没有样本就整段省掉，不补 0 —— 补 0 会被读成「压力为零」。 */
    private static List<StressBucket> buckets(List<VitalsSample> samples, int bucketMinutes) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (VitalsSample sample : samples) {
            grouped.computeIfAbsent(bucketStart(sample.time(), bucketMinutes), key -> new ArrayList<>())
                    .add(sample.value());
        }
        List<StressBucket> out = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<Double>> entry : grouped.entrySet()) {
            List<Double> values = entry.getValue();
            double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            int max = (int) Math.round(values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
            out.add(new StressBucket(entry.getKey(), values.size(),
                    PaceFormat.round(average, 1), max, level(average)));
        }
        return out;
    }

    /** 各档位的样本数。不换算成分钟：中间有大量没测的空隙，换算会凭空造出时长。 */
    private static Map<String, Integer> levels(List<VitalsSample> samples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("relaxed", 0);
        counts.put("normal", 0);
        counts.put("moderate", 0);
        counts.put("high", 0);
        for (VitalsSample sample : samples) {
            counts.merge(level(sample.value()), 1, Integer::sum);
        }
        counts.values().removeIf(count -> count == 0);
        return counts;
    }

    /** 最高/最低的那一次测量；同值时取更早的那条，结果可复现。 */
    private static VitalsSample peak(List<VitalsSample> samples, boolean highest) {
        VitalsSample best = null;
        for (VitalsSample sample : samples) {
            if (best == null
                    || (highest ? sample.value() > best.value() : sample.value() < best.value())) {
                best = sample;
            }
        }
        return best;
    }

    /** 采样间隔中位数（分钟）；样本少于 2 条时返回 {@code null}。 */
    private static Integer medianGap(List<VitalsSample> samples) {
        if (samples.size() < 2) {
            return null;
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            Integer a = minutes(samples.get(i - 1).time());
            Integer b = minutes(samples.get(i).time());
            if (a != null && b != null && b >= a) {
                gaps.add(b - a);
            }
        }
        if (gaps.isEmpty()) {
            return null;
        }
        gaps.sort(Integer::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    /** 把 {@code HH:mm} 落到桶起点，例如 30 分钟桶下 {@code 13:47} → {@code 13:30}。 */
    private static String bucketStart(String time, int bucketMinutes) {
        Integer total = minutes(time);
        if (total == null) {
            return time;
        }
        int start = (total / bucketMinutes) * bucketMinutes;
        return String.format("%02d:%02d", start / 60, start % 60);
    }

    private static Integer minutes(String time) {
        if (time == null || time.length() < 5 || time.charAt(2) != ':') {
            return null;
        }
        try {
            int hour = Integer.parseInt(time.substring(0, 2));
            int minute = Integer.parseInt(time.substring(3, 5));
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 华为压力分档。{@code unknown} 只在值完全取不到时出现。 */
    static String level(double score) {
        if (Double.isNaN(score)) {
            return "unknown";
        }
        if (score < RELAXED_UPPER) {
            return "relaxed";
        }
        if (score < NORMAL_UPPER) {
            return "normal";
        }
        if (score < MODERATE_UPPER) {
            return "moderate";
        }
        return "high";
    }

    /** 返回体的口径提示。只写数据本身说不清、必须由工具补一句的东西。 */
    public List<String> notes(StressDay day) {
        List<String> notes = new ArrayList<>();
        if (day == null) {
            return notes;
        }
        notes.add("压力是约 30 分钟一次的离散测量，桶里给的是均值与极值；中间没测的时段不补值，"
                + "所以 buckets 之间可能有空档。");
        if (day.min() <= FLOOR_SCORE) {
            notes.add("当天最低分是 " + day.min() + " 分，等于算法下限 " + FLOOR_SCORE
                    + " —— 这不是「压力为零」，是设备测到更低也只会报这个数。"
                    + "全库 19.4% 的样本都落在这一档。");
        }
        if (day.max() < NORMAL_UPPER) {
            notes.add("全天最高只有 " + day.max() + " 分，没有进过「中等」（60 分）以上。"
                    + "全库实测最大值是 53 分 —— 这份数据的压力分整体低位，"
                    + "「没到高压」不代表那天不累，别用它否定主观感受。");
        }
        Integer gap = day.medianGapMinutes();
        if (gap != null && gap >= 29) {
            notes.add("采样间隔中位数 " + gap + " 分钟，共 " + day.samples()
                    + " 个点。夜间和静息时测得更勤，运动或摘表时会断。");
        }
        if (day.samples() < 10) {
            notes.add("当天只有 " + day.samples() + " 个样本，曲线基本看不出走势，"
                    + "只够判断大致水平。");
        }
        // 覆盖时段：末次测量早于 20:00 基本等于只戴了半个白天（或导出截断在这时），
        // 那天的「全天均值」不能代表一整天。
        Integer last = minutes(day.lastSample());
        if (last != null && last < LATE_HOUR * 60) {
            notes.add("当天采样只覆盖 " + day.firstSample() + "–" + day.lastSample()
                    + "，没有覆盖到晚上 —— 全天均值只代表这半天，"
                    + "可能只戴了半个白天或导出截断在这时。");
        }
        return notes;
    }
}
