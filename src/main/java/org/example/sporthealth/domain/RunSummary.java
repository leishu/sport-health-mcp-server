package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一个日期区间内的跑步汇总。所有比例类字段都由总距离与总时长重新计算，
 * 而不是对单次配速做平均，避免长短距离权重失真。
 *
 * @param from                  区间开始日期
 * @param to                    区间结束日期
 * @param dayCount              区间自然日数（含首尾）
 * @param runCount              记录条数
 * @param recordedDays          实际有记录的天数
 * @param totalDistanceKm       总距离（公里）
 * @param totalDurationMinutes  总时长（分钟）
 * @param totalClimbMeters      总爬升（米）
 * @param totalDescentMeters    总下降（米）
 * @param avgDistanceKm         平均每次距离（公里）
 * @param avgDurationMinutes    平均每次时长（分钟）
 * @param avgPaceSecondsPerKm   整体平均配速（秒/公里）
 * @param avgPaceText           平均配速的易读形式，例如 5'45"/km
 * @param bestPaceSecondsPerKm  区间内最快单次配速（秒/公里）
 * @param bestPaceRunId         最快配速对应的记录 id
 * @param longestRunId          最长距离对应的记录 id
 * @param longestDistanceKm     最长距离（公里）
 * @param firstRunDate          区间内最早记录日期
 * @param lastRunDate           区间内最晚记录日期
 */
public record RunSummary(
        LocalDate from,
        LocalDate to,
        int dayCount,
        int runCount,
        int recordedDays,
        double totalDistanceKm,
        double totalDurationMinutes,
        double totalClimbMeters,
        double totalDescentMeters,
        Double avgDistanceKm,
        Double avgDurationMinutes,
        Double avgPaceSecondsPerKm,
        String avgPaceText,
        Double bestPaceSecondsPerKm,
        String bestPaceRunId,
        String longestRunId,
        Double longestDistanceKm,
        LocalDate firstRunDate,
        LocalDate lastRunDate) {
}
