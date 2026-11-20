package org.example.sporthealth.domain;

/**
 * 相邻两个周期的差值：本周期 − 上一周期。
 *
 * <p>第一个周期的全部字段都是 {@code null}（没有「上一个」可比）；
 * 其余为 {@code null} 只表示「本周期或上一周期该指标缺数据」，
 * 所以「从无报告期到有报告期」的第一天不会出现一个凭空的大跳变。
 *
 * @param asleepMinutes     睡着时长差（分钟）
 * @param score             评分差（分）
 * @param efficiencyPercent 睡眠效率差（百分点）
 * @param hrvMs             HRV 差（毫秒）
 * @param wakeCount         醒来次数差（次）
 * @param deepPercent       深睡占比差（百分点）
 * @param latencyMinutes    入睡潜伏期差（分钟）
 */
public record SleepTrendDeltas(
        Integer asleepMinutes,
        Double score,
        Double efficiencyPercent,
        Double hrvMs,
        Double wakeCount,
        Double deepPercent,
        Double latencyMinutes) {

    /** 没有上一周期（或上一周期没数）时用这个。 */
    public static SleepTrendDeltas none() {
        return new SleepTrendDeltas(null, null, null, null, null, null, null);
    }
}
