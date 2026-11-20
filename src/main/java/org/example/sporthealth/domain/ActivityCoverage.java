package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 查询区间内「多少天真有活动记录」。
 *
 * <p>{@code days} 是区间自然日数，{@code recordedDays} 是 {@code daily_activity} 里真有的天数。
 * 两者差得多时，均值/达标天数都只代表有记录的那些天，不能说成「这一个月日均多少步」。
 *
 * <p>{@code sparseDays} 是「有记录但只有不到 20 条分钟数据」的天数：正常一天 120~500 条，
 * 只有个位数通常是没戴表、或导出那天还没过完。这类天的步数会异常低，
 * 判「活动量不够」之前先把它排除。
 *
 * @param days         区间自然日数（含首尾）
 * @param recordedDays 有活动记录的天数
 * @param missingDays  没有任何记录的天数
 * @param sparseDays   有记录但分钟数据少于 20 条的天数（可能没戴表 / 导出未完成）
 * @param firstDay     区间内最早有记录的一天
 * @param lastDay      区间内最晚有记录的一天
 */
public record ActivityCoverage(
        int days,
        int recordedDays,
        int missingDays,
        int sparseDays,
        LocalDate firstDay,
        LocalDate lastDay) {
}
