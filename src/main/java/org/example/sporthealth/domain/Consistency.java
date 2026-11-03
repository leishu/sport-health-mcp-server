package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 跑步连续性与打卡情况。
 *
 * <p>{@code currentStreak} 以区间内最后一个有记录的自然日为锚点，而不是今天：
 * 历史数据导出后，“今天”通常没有记录，用今天做锚点会让连续天数永远是 0。
 *
 * @param from             统计区间开始日期
 * @param to               统计区间结束日期
 * @param activeDays       有记录的天数
 * @param totalDays        区间自然日数
 * @param activeRatePercent 有记录天数占比（0~100）
 * @param currentStreak    截至最后一次记录的连续天数
 * @param longestStreak    区间内最长连续天数
 * @param avgGapDays       相邻记录日之间的平均间隔天数，少于 2 天时为 null
 * @param lastActiveDate   区间内最后一次记录的日期
 * @param days             有记录的日期明细，按日期升序
 * @param text             一句话结论
 */
public record Consistency(
        LocalDate from,
        LocalDate to,
        int activeDays,
        int totalDays,
        double activeRatePercent,
        Streak currentStreak,
        Streak longestStreak,
        Double avgGapDays,
        LocalDate lastActiveDate,
        List<ActiveDay> days,
        String text) {
}
