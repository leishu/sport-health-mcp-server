package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一个周期（周或月）的活动量聚合。
 *
 * <p>{@code complete = false} 表示这个周期被查询区间截断，只统计了其中一部分天；
 * 首末两个周期经常如此。截断周期的合计（步数、热量）不能和完整周期直接比
 * —— 那可能只是「少了几天」，所以同时给出 {@code days} 与按天均值。
 *
 * @param period                    周期标识，周为 {@code 2026-W45}，月为 {@code 2026-11}
 * @param from                      周期起始日
 * @param to                        周期结束日
 * @param complete                  是否被查询区间截断
 * @param days                      周期内有记录的天数
 * @param stepsTotal                步数合计
 * @param stepsPerDay               日均步数
 * @param distanceKm                距离合计（公里）
 * @param caloriesKcal              活动热量合计（千卡）
 * @param activeMinutesPerDay       日均中高强度分钟
 * @param standEventsPerDay         日均站立次数
 * @param daysMeetingSteps          步数达标的天数
 * @param daysMeetingActiveMinutes  中高强度达标的天数
 */
public record ActivityPeriod(
        String period,
        LocalDate from,
        LocalDate to,
        boolean complete,
        int days,
        long stepsTotal,
        int stepsPerDay,
        double distanceKm,
        double caloriesKcal,
        double activeMinutesPerDay,
        double standEventsPerDay,
        int daysMeetingSteps,
        int daysMeetingActiveMinutes) {
}
