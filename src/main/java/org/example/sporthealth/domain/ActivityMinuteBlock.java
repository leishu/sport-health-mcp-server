package org.example.sporthealth.domain;

/**
 * 一天里的一段连续活动。
 *
 * <p>把「同一类型、前后相接（允许 ≤2 分钟空档）的分钟」拼成一段：用户问「某一天每分钟」，
 * 真正想看的是「早上 06:02~07:04 跑了 62 分钟」这种时间线，而不是两百行流水。
 * 原始分钟仍然在 {@code minutes} 里，两者互补。
 *
 * <p>允许 2 分钟空档是因为手表会漏记个别分钟（走神、电梯、信号），严格相邻会把一次跑步
 * 拆成七八段。空档不算进 {@code minutes}，所以 {@code minutes} 可能略小于
 * {@code from}~{@code to} 的跨度。
 *
 * @param type         类型键：{@code walk} / {@code run} / {@code other}
 * @param label        类型中文名
 * @param from         这一段的起点，{@code HH:mm}
 * @param to           这一段的终点（最后一条记录的结束时间），{@code HH:mm}
 * @param minutes      段内的分钟记录条数（≈ 实际活动分钟，不含空档）
 * @param steps        段内步数合计
 * @param distanceKm   段内距离合计（公里）
 * @param caloriesKcal 段内活动热量合计（千卡）
 */
public record ActivityMinuteBlock(
        String type,
        String label,
        String from,
        String to,
        int minutes,
        int steps,
        double distanceKm,
        double caloriesKcal) {
}
