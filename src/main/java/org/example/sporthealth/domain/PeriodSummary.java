package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 按天 / 周 / 月聚合后的一个分组，字段口径与 {@link DailyRunSummary} 一致。
 *
 * @param from             分组开始日期（含）
 * @param to               分组结束日期（含）
 * @param label            展示名
 * @param runCount         记录条数
 * @param distanceKm       总距离（公里）
 * @param durationMinutes  总时长（分钟）
 * @param paceSecondsPerKm 整体平均配速（秒/公里）
 * @param paceText         平均配速文本
 * @param climbMeters      总爬升（米）
 * @param runIds           分组内记录 id，按开始时间升序
 */
public record PeriodSummary(
        LocalDate from,
        LocalDate to,
        String label,
        int runCount,
        double distanceKm,
        double durationMinutes,
        Double paceSecondsPerKm,
        String paceText,
        double climbMeters,
        List<String> runIds) {
}
