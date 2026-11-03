package org.example.sporthealth.domain;

/**
 * 两次跑步的差异对比，所有差值均为 {@code other - base}：正数表示 other 更大/更慢。
 *
 * @param base                    基准记录
 * @param other                   对比记录
 * @param distanceDiffKm          距离差（公里）
 * @param durationDiffMinutes     时长差（分钟）
 * @param paceDiffSecondsPerKm    配速差（秒/公里）；任一侧缺配速时为 null
 * @param verdict                 一句话结论，便于直接回读给用户
 */
public record RunComparison(
        RunActivity base,
        RunActivity other,
        Double distanceDiffKm,
        Double durationDiffMinutes,
        Double paceDiffSecondsPerKm,
        String verdict) {
}
