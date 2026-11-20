package org.example.sporthealth.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.HeartRateActivity;
import org.example.sporthealth.domain.HeartRateBucket;
import org.example.sporthealth.domain.HeartRateDayStats;
import org.example.sporthealth.domain.HeartRateExtreme;
import org.example.sporthealth.domain.HeartRateSample;
import org.example.sporthealth.domain.HeartRateSeries;
import org.example.sporthealth.repository.HeartRateSeriesStore;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.stereotype.Service;

/**
 * 把一天几千个自动心率采样压成「一条能读的曲线 + 几个结论」。
 *
 * <p>三件事必须在这里做，不能推到工具层：
 * <ul>
 *   <li><b>分桶</b>：原始点最多 1439 个（每分钟一条），直接返回会挤爆上下文；</li>
 *   <li><b>峰值/谷值的持续时长</b>：单点数值分不清「跑步顶到 153」和「翻身蹭到 94」，
 *       只有看它前后有没有连续的相近值才能判断；</li>
 *   <li><b>把峰值和运动时段对上</b>：曲线上的高台基本都是当天的运动，
 *       标出时段就不会被读成异常心率。</li>
 * </ul>
 *
 * <p>不在这里判断训练强度（Z1~Z5、储备心率百分比）：那是跑步分析的活，
 * 心率区间口径只允许有一处实现，避免两套百分比互相打架。
 */
@Service
public class HeartRateSeriesService {

    /** 峰值/谷值判定「同样高/同样低」的容差，单位 bpm。 */
    private static final int EXTREME_TOLERANCE = 5;

    /** 采样间隔不超过这个分钟数，才算「连续」——比它更长就是中间断了，不能连起来算持续时长。 */
    private static final int MIN_GAP_LIMIT_MINUTES = 5;

    /** 全天采样跨度少于这个分钟数，就提示「可能只戴了半天」。 */
    private static final int SHORT_COVERAGE_MINUTES = 20 * 60;

    /** 超过这个分钟数的空档值得单独提示。 */
    private static final int NOTABLE_GAP_MINUTES = 120;

    private final HeartRateSeriesStore store;

    public HeartRateSeriesService(HeartRateSeriesStore store) {
        this.store = store;
    }

    /** 允许的粒度；工具层用它做 {@code INVALID_ARGUMENT} 的提示。 */
    public static List<String> resolutions() {
        return List.of("minute", "5min", "15min", "hour");
    }

    /** 粒度换算成桶的分钟数；认不出的返回 0，由工具层转成参数错误。 */
    public static int bucketMinutes(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return 5;
        }
        return switch (resolution.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "minute" -> 1;
            case "5min" -> 5;
            case "15min" -> 15;
            case "hour" -> 60;
            default -> 0;
        };
    }

    /** 归一化粒度名字；认不出返回 {@code null}，不静默退回默认值。 */
    public static String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return "5min";
        }
        String key = resolution.trim().toLowerCase(java.util.Locale.ROOT);
        return bucketMinutes(key) == 0 ? null : key;
    }

    /** 数据里最后一天有自动心率的日子。 */
    public LocalDate latestDate() {
        return store.latestDate().orElse(null);
    }

    /** 全库可用范围，给 {@code NO_DATA} 用。 */
    public String coveredRange() {
        return store.coveredRange().orElse(null);
    }

    /**
     * 某一天的曲线。
     *
     * @param resolution 已归一化的粒度名
     * @return 当天一个采样点都没有时返回 {@code null}
     */
    public HeartRateSeries series(LocalDate date, String resolution) {
        List<HeartRateSample> samples = store.samples(date);
        if (samples.isEmpty()) {
            return null;
        }
        int bucketMinutes = bucketMinutes(resolution);
        HeartRateDayStats stats = stats(samples);
        int gapLimit = Math.max(MIN_GAP_LIMIT_MINUTES,
                stats.medianGapMinutes() == null ? MIN_GAP_LIMIT_MINUTES : stats.medianGapMinutes() * 2);

        int peakIndex = indexOfExtreme(samples, true);
        int lowestIndex = indexOfExtreme(samples, false);
        HeartRateExtreme peak = extreme(samples, peakIndex, gapLimit, true);
        HeartRateExtreme lowest = extreme(samples, lowestIndex, gapLimit, false);

        List<HeartRateActivity> activities = store.activities(date);
        return new HeartRateSeries(
                date,
                resolution,
                bucketMinutes,
                stats,
                peak,
                lowest,
                buckets(samples, bucketMinutes),
                activities,
                notes(stats, peak, activities));
    }

    /**
     * 全天统计。
     *
     * <p>{@code samples} 与 {@code daily_vitals.heart_rate_samples} 逐日相等（同一张表、同一个
     * {@code count(*)}），所以这里算出来的 min/avg/max 也必然和 {@code get_daily_vitals}
     * 的 {@code heartRate} 完全一致 —— 两个工具对同一天给出不同数字才是 bug。
     */
    private static HeartRateDayStats stats(List<HeartRateSample> samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        List<Integer> gaps = new ArrayList<>();
        int maxGap = 0;
        for (int i = 0; i < samples.size(); i++) {
            HeartRateSample sample = samples.get(i);
            min = Math.min(min, sample.bpm());
            max = Math.max(max, sample.bpm());
            sum += sample.bpm();
            if (i > 0) {
                int gap = minutesBetween(samples.get(i - 1).time(), sample.time());
                gaps.add(gap);
                maxGap = Math.max(maxGap, gap);
            }
        }
        Integer medianGap = null;
        if (!gaps.isEmpty()) {
            List<Integer> sorted = gaps.stream().sorted().toList();
            // 偶数个就取中间两个的平均；间隔是整数，四舍五入回整数便于阅读
            int half = sorted.size() / 2;
            medianGap = sorted.size() % 2 == 1
                    ? sorted.get(half)
                    : (int) Math.round((sorted.get(half - 1) + sorted.get(half)) / 2d);
        }
        return new HeartRateDayStats(
                samples.size(),
                min,
                max,
                PaceFormat.round((double) sum / samples.size(), 1),
                samples.get(0).time(),
                samples.get(samples.size() - 1).time(),
                minutesBetween(samples.get(0).time(), samples.get(samples.size() - 1).time()),
                medianGap,
                maxGap);
    }

    /** 分段聚合。空桶不补：一天 288 个桶里一半是 null 的话，曲线反而更难读。 */
    private static List<HeartRateBucket> buckets(List<HeartRateSample> samples, int bucketMinutes) {
        Map<Integer, int[]> accumulator = new LinkedHashMap<>();
        for (HeartRateSample sample : samples) {
            int minuteOfDay = minuteOfDay(sample.time());
            int slot = minuteOfDay / bucketMinutes * bucketMinutes;
            int[] cell = accumulator.computeIfAbsent(slot, key -> new int[]{Integer.MAX_VALUE, 0, Integer.MIN_VALUE, 0});
            cell[0] = Math.min(cell[0], sample.bpm());
            cell[1] += sample.bpm();
            cell[2] = Math.max(cell[2], sample.bpm());
            cell[3]++;
        }
        List<HeartRateBucket> out = new ArrayList<>(accumulator.size());
        accumulator.forEach((slot, cell) -> out.add(new HeartRateBucket(
                clockOfMinute(slot),
                cell[0],
                PaceFormat.round((double) cell[1] / cell[3], 1),
                cell[2],
                cell[3])));
        out.sort(Comparator.comparing(HeartRateBucket::time));
        return out;
    }

    private static int indexOfExtreme(List<HeartRateSample> samples, boolean highest) {
        int index = 0;
        for (int i = 1; i < samples.size(); i++) {
            int current = samples.get(i).bpm();
            int best = samples.get(index).bpm();
            if (highest ? current > best : current < best) {
                index = i;
            }
        }
        return index;
    }

    /**
     * 围绕极值点向两侧扩展，统计「连续保持在 ±{@value #EXTREME_TOLERANCE} bpm 以内的时长」。
     *
     * <p>向两侧看的时候一旦遇到间隔超过 {@code gapLimit} 就停：中间断了半小时的两段高心率
     * 不能算成一段持续 30 分钟的高心率。
     */
    private static HeartRateExtreme extreme(List<HeartRateSample> samples, int index, int gapLimit,
                                            boolean highest) {
        HeartRateSample pivot = samples.get(index);
        int bound = highest
                ? pivot.bpm() - EXTREME_TOLERANCE
                : pivot.bpm() + EXTREME_TOLERANCE;

        int start = index;
        for (int i = index - 1; i >= 0; i--) {
            if (minutesBetween(samples.get(i).time(), samples.get(i + 1).time()) > gapLimit) {
                break;
            }
            if (!within(samples.get(i).bpm(), bound, highest)) {
                break;
            }
            start = i;
        }
        int end = index;
        for (int i = index + 1; i < samples.size(); i++) {
            if (minutesBetween(samples.get(i - 1).time(), samples.get(i).time()) > gapLimit) {
                break;
            }
            if (!within(samples.get(i).bpm(), bound, highest)) {
                break;
            }
            end = i;
        }
        // 只有一个采样点时跨度是 0，按「至少 1 分钟」算
        int span = minutesBetween(samples.get(start).time(), samples.get(end).time()) + 1;
        return new HeartRateExtreme(pivot.time(), pivot.bpm(), Math.max(1, span));
    }

    private static boolean within(int bpm, int bound, boolean highest) {
        return highest ? bpm >= bound : bpm <= bound;
    }

    /**
     * 口径提示。只写「数据本身说不清、必须由工具补一句」的东西，不复述返回体里的数字。
     */
    private static List<String> notes(HeartRateDayStats stats, HeartRateExtreme peak,
                                      List<HeartRateActivity> activities) {
        List<String> notes = new ArrayList<>();
        Integer median = stats.medianGapMinutes();

        if (median != null && median >= 5) {
            notes.add("当天是稀疏采样：中位间隔 " + median + " 分钟、共 " + stats.samples()
                    + " 个点。设备在静息时会拉长间隔、运动时才加密到 1 分钟，点数少不代表没戴表。");
        } else if (median != null && median == 1) {
            notes.add("当天按分钟采样（" + stats.samples() + " 个点），曲线分辨率到分钟。");
        }

        if (stats.maxGapMinutes() >= NOTABLE_GAP_MINUTES) {
            notes.add("最长有 " + stats.maxGapMinutes() + " 分钟没有任何采样，那段曲线是断的。");
        }

        if (stats.spanMinutes() < SHORT_COVERAGE_MINUTES) {
            notes.add("当天采样只覆盖 " + stats.firstSample() + "–" + stats.lastSample()
                    + "，可能只戴了半天或导出截断在这时，全天均值不代表一整天。");
        }

        // 峰值落在运动时段里时，「孤立尖峰」的说法会和「运动峰值」自相矛盾 —— 只留后者。
        // 冲刺本来就可能只顶到 1 分钟，那时前一条提示是错的。
        HeartRateActivity host = activities.stream()
                .filter(activity -> activity.start() != null && activity.end() != null
                        && peak.time().compareTo(activity.start()) >= 0
                        && peak.time().compareTo(activity.end()) <= 0)
                .findFirst().orElse(null);
        if (host != null) {
            notes.add("最高心率落在" + host.sport() + " " + host.start() + "–" + host.end() + " 时段内"
                    + (host.maxHeartRate() == null ? "" : "（该次记录最高 " + host.maxHeartRate()
                    + "、平均 " + host.avgHeartRate() + "）")
                    + "，属于运动峰值而非异常。");
        } else if (peak.sustainedMinutes() <= 1) {
            notes.add("最高点 " + peak.bpm() + " bpm 只出现在 " + peak.time()
                    + " 这一个采样里、前后没有 ±" + EXTREME_TOLERANCE + " 以内的值，也不在任何运动时段内，"
                    + "更像瞬时动作或传感器噪声，不要当成有效峰值。");
        }
        return notes;
    }

    private static int minuteOfDay(String clock) {
        LocalTime time = LocalTime.parse(clock);
        return time.getHour() * 60 + time.getMinute();
    }

    private static int minutesBetween(String from, String to) {
        return minuteOfDay(to) - minuteOfDay(from);
    }

    private static String clockOfMinute(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
