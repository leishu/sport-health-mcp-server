package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一天的活动量快照（{@code daily_activity} 一行）。
 *
 * <p>数据来自华为「每分钟运动数据」的按天汇总，所以 {@code steps} 是<b>这一天所有分钟记录的步数之和</b>：
 * 同一分钟同时有走路与跑步两条记录时会各算一次，比华为 App 自报的略高（全库约 0.8%）。
 * {@code walkSteps} / {@code runSteps} 分列出来，需要严格口径时可以只取其中一类。
 *
 * <p>{@code standEvents} 是<b>站立事件条数</b>，不是分钟数：华为把站立记成整点的
 * {@code sportType=13} 事件（一天 3~22 条），对应 App 里的「站立 N/12 次」。
 *
 * <p>{@code caloriesKcal} 是<b>活动热量</b>（每条分钟记录里的 calorie 字段之和），
 * 不含基础代谢，也不是全天总消耗 —— 和「今天一共消耗多少千卡」不是一回事。
 *
 * <p>三个 {@code *GoalMet} 由调用方按目标值判定后填入，数据库里没有这几列。
 *
 * @param date                 日期
 * @param steps                步数（含走路 + 跑步；同分钟双记录会重复计）
 * @param walkSteps            其中来自走路（{@code sportType=5}）的步数
 * @param runSteps             其中来自跑步（{@code sportType=4}）的步数
 * @param distanceKm           距离（公里）
 * @param caloriesKcal         活动热量（千卡）
 * @param activeMinutes        中高强度分钟数（{@code is_intensity=1} 的分钟数之和）
 * @param standEvents          站立事件条数（{@code sportType=13}）
 * @param minuteRows           当天有活动记录的分钟条数；个位数说明表基本没戴或导出没完成
 * @param stepsGoalMet         步数是否达到当日目标
 * @param activeMinutesGoalMet 中高强度分钟是否达到当日目标
 * @param standGoalMet         站立次数是否达到当日目标
 */
public record DailyActivityDay(
        LocalDate date,
        int steps,
        int walkSteps,
        int runSteps,
        double distanceKm,
        double caloriesKcal,
        int activeMinutes,
        int standEvents,
        int minuteRows,
        boolean stepsGoalMet,
        boolean activeMinutesGoalMet,
        boolean standGoalMet) {
}
