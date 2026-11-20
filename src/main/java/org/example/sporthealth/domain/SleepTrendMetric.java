package org.example.sporthealth.domain;

import java.util.Locale;

/**
 * 趋势的主指标：决定「斜率算的是哪一条线」。
 *
 * <p>每个指标都自带三样东西，缺一条都会让趋势结论失真：
 * <ul>
 *   <li>{@code unit}：斜率要带单位，否则「每周 +1.7」不知道是分钟还是分；</li>
 *   <li>{@code higherIsBetter}：醒来次数涨了是变差，睡着时长涨了是变好，
 *       光给 {@code up}/{@code down} 会让调用方自己猜方向含义；</li>
 *   <li>{@code minMeaningfulChange}：一周差 0.2 分钟算不上「变长」，
 *       小于这个量级的斜率一律判 flat，避免把噪声说成趋势。</li>
 * </ul>
 */
public enum SleepTrendMetric {

    /** 平均睡着时长，分钟。 */
    ASLEEP("asleep", "minutes", true, 5d),
    /** 平均睡眠评分，分。 */
    SCORE("score", "points", true, 1d),
    /** 平均睡眠效率，%。 */
    EFFICIENCY("efficiency", "percent", true, 1d),
    /** 平均 HRV，毫秒。 */
    HRV("hrv", "ms", true, 2d),
    /** 平均醒来次数，次。越少越好。 */
    WAKE_COUNT("wakeCount", "count", false, 0.3d),
    /** 深睡占睡着时长的百分比，%。 */
    DEEP("deep", "percent", true, 1d),
    /** 平均入睡潜伏期，分钟。越短越好。 */
    LATENCY("latency", "minutes", false, 3d);

    private final String key;
    private final String unit;
    private final boolean higherIsBetter;
    private final double minMeaningfulChange;

    SleepTrendMetric(String key, String unit, boolean higherIsBetter, double minMeaningfulChange) {
        this.key = key;
        this.unit = unit;
        this.higherIsBetter = higherIsBetter;
        this.minMeaningfulChange = minMeaningfulChange;
    }

    /** 输出里用的标识，也是入参的规范值。 */
    public String key() {
        return key;
    }

    public String unit() {
        return unit;
    }

    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    public double minMeaningfulChange() {
        return minMeaningfulChange;
    }

    /**
     * 解析入参。只认少量常见写法（大小写、下划线、加不加单位后缀都能识别），
     * 认不出来返回 {@code null} 由调用方转成 {@code INVALID_ARGUMENT}——
     * 静默退回默认指标会让「我想看 HRV 趋势」的请求拿到睡着时长的斜率。
     */
    public static SleepTrendMetric parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "asleep", "asleepminutes", "sleep", "sleepduration", "duration" -> ASLEEP;
            case "score", "sleepscore" -> SCORE;
            case "efficiency", "efficiencypercent", "efficiencypct" -> EFFICIENCY;
            case "hrv", "hrvms" -> HRV;
            case "wakecount", "wakecounts", "wake", "wakes" -> WAKE_COUNT;
            case "deep", "deeppercent", "deepsleep", "deeppercentofsleep" -> DEEP;
            case "latency", "sleeplatency", "latencyminutes" -> LATENCY;
            default -> null;
        };
    }

    /** 这个指标在一个周期上的取值；没有数据时返回 {@code null}（不补 0）。 */
    public Double valueOf(SleepTrendValues values) {
        if (values == null) {
            return null;
        }
        return switch (this) {
            case ASLEEP -> values.asleepMinutes() == null ? null : values.asleepMinutes().doubleValue();
            case SCORE -> values.score();
            case EFFICIENCY -> values.efficiencyPercent();
            case HRV -> values.hrvMs();
            case WAKE_COUNT -> values.wakeCount();
            case DEEP -> values.deepPercent();
            case LATENCY -> values.latencyMinutes();
        };
    }
}
