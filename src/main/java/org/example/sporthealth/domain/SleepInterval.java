package org.example.sporthealth.domain;

/**
 * 一段连续区间（清醒段、睡眠碎片、午睡段都用它）。
 *
 * <p>时刻一律是 {@code yyyy-MM-dd HH:mm:ss}：睡眠详情会被 LLM 直接读，
 * 缺了日期只写 {@code 01:29} 会在跨午夜时被误读成当天凌晨。
 *
 * @param start   开始时刻
 * @param end     结束时刻
 * @param minutes 时长（分钟）
 */
public record SleepInterval(
        String start,
        String end,
        int minutes) {
}
