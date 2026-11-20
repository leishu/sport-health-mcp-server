package org.example.sporthealth.domain;

/**
 * 一个周期上的各项睡眠指标。
 *
 * <p>全部是「先逐夜取值、再平均」的口径，分母只算有值的夜晚（见 {@code SleepTrendPoint.reportedNights}），
 * 所以 {@code null} 表示这个周期没有这类数据，不是 0：
 * 评分/效率/HRV/潜伏期来自官方报告，2026-06-13 之前一律为 {@code null}。
 *
 * @param asleepMinutes     平均睡着时长（分钟）
 * @param score             平均睡眠评分
 * @param efficiencyPercent 平均睡眠效率（%）
 * @param hrvMs             平均 HRV（毫秒）
 * @param wakeCount         平均醒来次数（分期口径，≥2 分钟才算一次）
 * @param deepPercent       深睡占睡着时长的百分比
 * @param latencyMinutes    平均入睡潜伏期（分钟）
 */
public record SleepTrendValues(
        Integer asleepMinutes,
        Double score,
        Double efficiencyPercent,
        Double hrvMs,
        Double wakeCount,
        Double deepPercent,
        Double latencyMinutes) {
}
