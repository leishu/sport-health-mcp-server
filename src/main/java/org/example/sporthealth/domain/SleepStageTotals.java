package org.example.sporthealth.domain;

/**
 * 睡眠各阶段合计，取自 {@code health_metric} 里 type=9 的分期区间。
 *
 * <p>百分比的分母是<b>睡着时长</b>（深睡 + 浅睡 + 快速眼动），不是卧床时长，
 * 所以睡眠效率低的一夜三个百分比加起来仍然是 100。
 *
 * @param deepMinutes           深睡分钟
 * @param lightMinutes          浅睡分钟
 * @param remMinutes            快速眼动分钟
 * @param wakeMinutes           窗口内清醒分钟（含入睡前与起床前，比 {@code awakeMinutes} 的 WASO 口径大）
 * @param deepPercentOfSleep    深睡占睡着时长百分比
 * @param lightPercentOfSleep   浅睡占睡着时长百分比
 * @param remPercentOfSleep     快速眼动占睡着时长百分比
 */
public record SleepStageTotals(
        Integer deepMinutes,
        Integer lightMinutes,
        Integer remMinutes,
        Integer wakeMinutes,
        Double deepPercentOfSleep,
        Double lightPercentOfSleep,
        Double remPercentOfSleep) {
}
