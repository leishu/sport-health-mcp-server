package org.example.sporthealth.domain;

/**
 * 一个日期区间的睡眠汇总，全部是「每个夜晚先取值、再平均」的口径。
 *
 * <p>每个平均值都配一个计数，说明它是拿几个夜晚算出来的：
 * 评分/效率来自睡眠报告（{@code reportNights}），HRV 与血氧在报告里也常有缺值
 * （血氧几乎常年缺），所以 {@code avgSpo2Percent} 为 {@code null} 只表示「这段区间没有可用的血氧」，
 * 不代表「血氧是 0」。分母固定是 {@code nights}，不做任何 0 值填充。
 *
 * @param nights                统计的夜晚数
 * @param avgAsleepMinutes      平均睡着时长（分钟）
 * @param avgAsleepText         平均睡着时长的可读写法，例如 {@code 6h 52m}
 * @param avgTimeInBedMinutes   平均卧床时长（分钟）
 * @param avgAwakeMinutes       平均夜间清醒时长（分钟，WASO 口径：不含入睡潜伏期与起床前清醒）
 * @param avgWakeCount          平均醒来次数
 * @param avgSleepLatencyMinutes 平均入睡潜伏期（分钟），只有报告有
 * @param avgDeepPercent        深睡占睡着时长的百分比（按区间总深睡 / 总睡着算，按时长加权）
 * @param avgRemPercent         快速眼动占睡着时长的百分比（同上）
 * @param avgScore              平均睡眠评分
 * @param reportNights          参与评分与效率平均的夜晚数
 * @param avgEfficiencyPercent  平均睡眠效率（%）
 * @param avgHrvMs              平均 HRV（毫秒）
 * @param hrvNights             参与 HRV 平均的夜晚数
 * @param avgSpo2Percent        平均血氧（%）
 * @param spo2Nights            参与血氧平均的夜晚数
 * @param stageNights           有分期数据（能算深睡/REM 占比）的夜晚数
 */
public record SleepSummary(
        int nights,
        Integer avgAsleepMinutes,
        String avgAsleepText,
        Integer avgTimeInBedMinutes,
        Integer avgAwakeMinutes,
        Double avgWakeCount,
        Double avgSleepLatencyMinutes,
        Double avgDeepPercent,
        Double avgRemPercent,
        Double avgScore,
        int reportNights,
        Double avgEfficiencyPercent,
        Double avgHrvMs,
        int hrvNights,
        Double avgSpo2Percent,
        int spo2Nights,
        int stageNights) {
}
