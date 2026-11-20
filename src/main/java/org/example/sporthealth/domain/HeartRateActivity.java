package org.example.sporthealth.domain;

/**
 * 当天的一次运动记录，用来解释心率曲线里的尖峰。
 *
 * <p>取自 {@code run_activity}（不只跑步，也包括健走与其它运动）：心率曲线上的高台
 * 基本都落在这些时段里，把时段标出来，调用方就不会把一个正常的跑步峰值读成异常。
 *
 * @param sport        运动名称，如「户外跑步」
 * @param start        开始时刻（{@code HH:mm}）
 * @param end          结束时刻（{@code HH:mm}）
 * @param distanceKm   距离（公里），无距离的运动为 0
 * @param avgHeartRate 本次运动记录的平均心率，可能为 null
 * @param maxHeartRate 本次运动记录的最高心率，可能为 null
 */
public record HeartRateActivity(
        String sport,
        String start,
        String end,
        double distanceKm,
        Integer avgHeartRate,
        Integer maxHeartRate) {
}
