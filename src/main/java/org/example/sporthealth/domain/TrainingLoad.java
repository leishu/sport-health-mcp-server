package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 训练负荷与伤病风险（ACWR）快照。
 *
 * <p>负荷口径可选距离（公里）或时长（分钟）：没有心率数据时，这是最稳定的替代指标。
 * 急性窗口固定 7 天，慢性窗口固定 28 天；{@code acwr = 急性负荷 / (慢性负荷 / 4)}。
 *
 * <p>数据覆盖不足时仍返回 ACWR，但 {@code confidence} 会降为 LOW，并在建议里明确说明，
 * 避免用 10 天数据给出“高伤病风险”的确定结论。
 *
 * @param anchorDate              评估截止日期（含）
 * @param metric                  负荷口径：distance 或 duration
 * @param acute                   最近 7 天窗口
 * @param chronic                 最近 28 天窗口
 * @param chronicWeeklyAverage    慢性周均负荷（慢性负荷 / 4）
 * @param acwr                    急性/慢性负荷比，慢性负荷为 0 时为 null
 * @param chronicCoverageDays     慢性窗口内实际有数据覆盖的自然日数
 * @param dataSufficient          覆盖天数与训练日是否足够支撑 ACWR 解读
 * @param confidence              数据置信度：HIGH / MEDIUM / LOW
 * @param riskLevel               INSUFFICIENT_DATA / UNDERTRAINING / OPTIMAL / CAUTION / HIGH_RISK
 * @param weeklyRampPercent       最近 7 天相对前 7 天的负荷变化百分比
 * @param consecutiveTrainingDays 截至 anchorDate 的连续训练天数
 * @param restDaysLast7           最近 7 天里没有训练的自然日数
 * @param monotony                7 天每日负荷的均值/标准差（Foster 单调性），无法计算时为 null
 * @param strain                  周负荷 × 单调性，无法计算时为 null
 * @param recommendation          面向用户的调整建议
 * @param text                    一句话结论
 */
public record TrainingLoad(
        LocalDate anchorDate,
        String metric,
        LoadWindow acute,
        LoadWindow chronic,
        Double chronicWeeklyAverage,
        Double acwr,
        int chronicCoverageDays,
        boolean dataSufficient,
        String confidence,
        String riskLevel,
        Double weeklyRampPercent,
        Integer consecutiveTrainingDays,
        Integer restDaysLast7,
        Double monotony,
        Double strain,
        String recommendation,
        String text) {

    /**
     * 一个负荷窗口。
     *
     * @param days            窗口自然日数
     * @param runCount        记录条数
     * @param trainingDays    有记录的自然日数
     * @param distanceKm      窗口内总距离（公里）
     * @param durationMinutes 窗口内总时长（分钟）
     * @param load            按 {@code metric} 口径计算的负荷
     */
    public record LoadWindow(
            int days,
            int runCount,
            int trainingDays,
            double distanceKm,
            double durationMinutes,
            double load) {
    }
}
