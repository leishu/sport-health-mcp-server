package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 按周 / 月聚合后的一个睡眠周期。
 *
 * <p>{@code from}/{@code to} 是周期边界与查询区间的交集（区间从周三开始时，这一周的
 * {@code from} 就是周三），{@code period} 保持周期本身的编号，方便跨查询对齐。
 *
 * @param period               周期编号，周为 {@code 2026-W24}，月为 {@code 2026-06}
 * @param from                 周期内被区间覆盖到的第一天
 * @param to                   周期内被区间覆盖到的最后一天
 * @param nights               周期内的夜晚数
 * @param avgAsleepMinutes     平均睡着时长（分钟）
 * @param avgAsleepText        平均睡着时长的可读写法
 * @param avgScore             平均睡眠评分，无报告时为 null
 * @param reportedNights       参与评分/效率平均的夜晚数
 * @param avgEfficiencyPercent 平均睡眠效率（%），无报告时为 null
 * @param avgHrvMs             平均 HRV（毫秒），无数据时为 null
 * @param avgSpo2Percent       平均血氧（%），无数据时为 null
 * @param avgWakeCount         平均醒来次数
 * @param spo2Nights           参与血氧平均的夜晚数
 */
public record SleepPeriod(
        String period,
        LocalDate from,
        LocalDate to,
        int nights,
        Integer avgAsleepMinutes,
        String avgAsleepText,
        Double avgScore,
        int reportedNights,
        Double avgEfficiencyPercent,
        Double avgHrvMs,
        Double avgSpo2Percent,
        Double avgWakeCount,
        int spo2Nights) {
}
