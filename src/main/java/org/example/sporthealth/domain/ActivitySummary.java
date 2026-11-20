package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 查询区间内的活动量概览。
 *
 * <p>所有均值都只在<b>有记录的天</b>上算（分母是 {@code days}），不把没记录的天当 0 ——
 * 那样「这周平均 3000 步」可能只是三天没戴表。缺多少天看 {@code ActivityCoverage}。
 *
 * <p>{@code medianSteps} 和 {@code averageSteps} 一起给：步数分布很偏，一次 3.5 万步的长距离
 * 就能把周均值拉高两千步，中位数更能代表「平常一天走多少」。
 *
 * @param days                区间内有记录的天数（= 各均值的分母）
 * @param totalSteps          步数合计
 * @param averageSteps        日均步数
 * @param medianSteps         步数中位数
 * @param minSteps            最少的一天步数
 * @param minStepsDate        最少那一天
 * @param maxSteps            最多的一天步数
 * @param maxStepsDate        最多那一天
 * @param totalDistanceKm     距离合计（公里）
 * @param averageDistanceKm   日均距离（公里）
 * @param totalCaloriesKcal   活动热量合计（千卡）
 * @param averageCaloriesKcal 日均活动热量（千卡）
 * @param totalActiveMinutes  中高强度分钟合计
 * @param averageActiveMinutes 日均中高强度分钟
 * @param averageStandEvents  日均站立次数
 * @param lowActivityDays     步数低于 5000 的天数（久坐日；已排除分钟数据不全的天，
 *                            否则「导出那天只记了 6 分钟」会被当成久坐）
 */
public record ActivitySummary(
        int days,
        long totalSteps,
        int averageSteps,
        int medianSteps,
        int minSteps,
        LocalDate minStepsDate,
        int maxSteps,
        LocalDate maxStepsDate,
        double totalDistanceKm,
        double averageDistanceKm,
        double totalCaloriesKcal,
        double averageCaloriesKcal,
        int totalActiveMinutes,
        int averageActiveMinutes,
        double averageStandEvents,
        int lowActivityDays) {
}
