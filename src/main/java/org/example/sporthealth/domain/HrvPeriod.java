package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一个周期（周或月）的睡眠 HRV 聚合。
 *
 * <p>{@code complete = false} 表示这个周期被查询区间截断，只统计了其中一部分夜 —— 首末两个周期
 * 经常如此，{@code complete} 与 {@code nights} 是两个概念，别混：截断看前者，设备没记录看后者。
 *
 * @param period    周期标识，周为 {@code 2026-W45}，月为 {@code 2026-11}
 * @param from      周期起始日（与查询区间求交后的实际起点）
 * @param to        周期结束日（与查询区间求交后的实际终点）
 * @param complete  是否完整覆盖（没被查询区间截断）
 * @param nights    这个周期内有可用 HRV 的夜数
 * @param rmssd     睡眠 HRV 均值（毫秒）
 * @param min       周期内最低的一夜
 * @param max       周期内最高的一夜
 * @param dayRmssd  同期日间 HRV 均值，仅作对照
 */
public record HrvPeriod(
        String period,
        LocalDate from,
        LocalDate to,
        boolean complete,
        int nights,
        Double rmssd,
        Double min,
        Double max,
        Double dayRmssd) {
}
