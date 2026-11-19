package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一个区间内跑姿指标的汇总。
 *
 * <p>统计口径是「每条记录算一个样本」：先算出每次跑步的均值，再对这些均值取平均/极值，
 * 而不是把所有采样行混在一起平均。这样长跑不会因为采样行多（每 5 秒一行）就压过短跑，
 * 与「这段时间我平均触地时间多少」的直觉一致。单次详情里的采样均值可能与之相差 0.1 左右。
 *
 * @param runCount               参与汇总的记录数（有跑姿或步幅任一项）
 * @param postureRunCount        有跑姿三项的记录数
 * @param strideRunCount         能算出步幅的记录数
 * @param groundContactTimeMs    触地时间统计
 * @param verticalOscillationCm  垂直振幅统计
 * @param groundBalancePercent   触地平衡统计
 * @param strideLengthMeters     步幅统计
 * @param cadenceSpm             步频统计
 */
public record DynamicsSummary(
        int runCount,
        int postureRunCount,
        int strideRunCount,
        MetricStat groundContactTimeMs,
        MetricStat verticalOscillationCm,
        MetricStat groundBalancePercent,
        MetricStat strideLengthMeters,
        MetricStat cadenceSpm) {

    /**
     * 一项指标的分布。
     *
     * @param count   参与统计的记录数
     * @param average 平均值
     * @param min     最小值
     * @param max     最大值
     */
    public record MetricStat(int count, double average, double min, double max) {
    }

    /**
     * 趋势里的一格（一周或一个月）。
     *
     * @param key                     周期标识：周为 {@code 2026-W24}，月为 {@code 2026-06}
     * @param from                    周期起始日期
     * @param to                      周期结束日期
     * @param runCount                该周期内的记录数
     * @param groundContactTimeMs     触地时间均值
     * @param verticalOscillationCm   垂直振幅均值
     * @param groundBalancePercent    触地平衡均值
     * @param strideLengthMeters      步幅均值
     * @param cadenceSpm              步频均值
     */
    public record Period(
            String key,
            LocalDate from,
            LocalDate to,
            int runCount,
            Double groundContactTimeMs,
            Double verticalOscillationCm,
            Double groundBalancePercent,
            Double strideLengthMeters,
            Double cadenceSpm) {
    }
}
