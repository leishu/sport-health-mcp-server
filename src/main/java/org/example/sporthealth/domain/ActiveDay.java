package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 某个有记录的自然日，用于连续性与日历视图。
 *
 * @param date            日期
 * @param runCount        当天记录条数
 * @param distanceKm      当天距离（公里）
 * @param durationMinutes 当天时长（分钟）
 */
public record ActiveDay(
        LocalDate date,
        int runCount,
        double distanceKm,
        double durationMinutes) {
}
