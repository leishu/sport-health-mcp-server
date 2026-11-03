package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 某一天的跑步汇总。只包含有记录的自然日，按日期升序排列。
 *
 * <p>跨零点的跑步归入开始日期；同一天多次跑步会合并到一条。
 *
 * @param date              本地日期
 * @param runCount          当天记录条数
 * @param distanceKm        当天总距离（公里）
 * @param durationMinutes   当天总时长（分钟）
 * @param paceSecondsPerKm  当天整体平均配速（秒/公里）
 * @param paceText          平均配速的易读形式
 * @param climbMeters       当天总爬升（米）
 * @param runIds            当天涉及的记录 id，按开始时间升序
 */
public record DailyRunSummary(
        LocalDate date,
        int runCount,
        double distanceKm,
        double durationMinutes,
        Double paceSecondsPerKm,
        String paceText,
        double climbMeters,
        List<String> runIds) {
}
