package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一个周期（周或月）的体征聚合。
 *
 * <p>{@code complete = false} 表示这个周期被查询区间截断，只统计了其中一部分天；
 * 首末两个周期经常如此。截断周期的均值不能和完整周期直接比 —— 那可能只是「少了几天」。
 *
 * <p>{@code days} 是这个周期内有 {@code daily_vitals} 行的天数，不是周期长度，也不是任一指标的
 * 有效天数；每个指标各自的分母看 {@code get_daily_vitals} 返回的 {@code coverage}。
 * 早期那些只有 VO2max 的行也算一行，所以 {@code days} 可能大于 {@code restingHeartRate} 的有效天数。
 *
 * @param period             周期标识，周为 {@code 2026-W45}，月为 {@code 2026-11}
 * @param from               周期起始日（与查询区间求交后的实际起点）
 * @param to                 周期结束日（与查询区间求交后的实际终点）
 * @param complete           是否是被查询区间截断的周期
 * @param days               周期内有体征记录的天数
 * @param restingHeartRate   静息心率均值（bpm，取整）
 * @param hrv                HRV 均值（毫秒，一位小数）
 * @param stress             压力均值（0~100，一位小数）
 * @param stressMax          压力峰值（0~100）
 * @param vo2max             最大摄氧量均值（ml/kg/min）
 * @param averageHeartRate   全天采样心率均值（bpm）
 * @param minHeartRate       全天采样最低心率（bpm）
 * @param maxHeartRate       全天采样最高心率（bpm）
 */
public record VitalsPeriod(
        String period,
        LocalDate from,
        LocalDate to,
        boolean complete,
        int days,
        Integer restingHeartRate,
        Double hrv,
        Double stress,
        Integer stressMax,
        Double vo2max,
        Double averageHeartRate,
        Integer minHeartRate,
        Integer maxHeartRate) {
}
