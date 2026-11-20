package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 某个指标在<b>全库</b>的可用范围，用来解释「为什么这段区间里这个指标是空的」。
 *
 * <p>{@code days} 是「有值的天数」，与 {@code from~to} 的跨度可能相差很远：
 * VO2max 跨度 856 天但只有 104 天有值，HRV 跨度 152 天且天天有值。
 * 两者一比就知道是「设备没记」还是「记录稀疏」。
 *
 * @param metric 指标名，与返回体 {@code metrics} 的键一致
 * @param from   全库最早有值的日期
 * @param to     全库最晚有值的日期
 * @param days   有值的天数
 */
public record VitalsAvailability(String metric, LocalDate from, LocalDate to, int days) {

    /** 跨度天数（含首尾），用来和 {@link #days} 对比看出稀疏程度。 */
    public int spanDays() {
        return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    }
}
