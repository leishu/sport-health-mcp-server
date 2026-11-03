package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 训练趋势中的一个周期（周或月）。
 *
 * <p>{@code distanceDeltaPercent} 与 {@code paceDeltaSecondsPerKm} 都是
 * “本周期 - 上一周期”：距离百分比为正表示跑量增加，配速差为负表示变快。
 *
 * @param from                    周期开始日期
 * @param to                      周期结束日期
 * @param label                   展示名，例如 2026-10 或 2026-10-26 ~ 2026-11-01
 * @param runCount                记录条数
 * @param distanceKm              总距离（公里）
 * @param durationMinutes         总时长（分钟）
 * @param paceSecondsPerKm        整体平均配速（秒/公里）
 * @param paceText                平均配速文本
 * @param climbMeters             总爬升（米）
 * @param distanceDeltaPercent    相比上一周期的距离变化百分比，上一周期为 0 时为 null
 * @param paceDeltaSecondsPerKm   相比上一周期的配速变化（秒/公里），缺配速时为 null
 */
public record TrendPeriod(
        LocalDate from,
        LocalDate to,
        String label,
        int runCount,
        double distanceKm,
        double durationMinutes,
        Double paceSecondsPerKm,
        String paceText,
        double climbMeters,
        Double distanceDeltaPercent,
        Double paceDeltaSecondsPerKm) {
}
