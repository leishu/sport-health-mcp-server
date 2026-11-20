package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 某一天的血氧汇总。
 *
 * <p>{@code below95} 是「低于 95% 的样本数」，不是时长也不是事件数 ——
 * 设备每 10 分钟出一个点，一个持续 30 分钟的低氧会变成 3 条样本。
 * 真正的「低氧事件」看 {@link Spo2LowEvent}（设备自己下发的提醒）。
 *
 * @param date    日期
 * @param samples 当天样本数
 * @param min     当天最低血氧（%）
 * @param average 当天均值
 * @param max     当天最高
 * @param below95 低于 95% 的样本数
 */
public record Spo2Day(
        LocalDate date,
        int samples,
        int min,
        Double average,
        int max,
        int below95) {
}
