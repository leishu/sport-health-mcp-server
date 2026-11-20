package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 入睡前的白天午睡（{@code PROFESSIONAL_SLEEP_NOON}）。
 *
 * <p>只取「结束时刻早于当晚入睡」的午睡，并且只认 08:00~20:00 开始的段：
 * 这条指标偶尔会把夜睡粘进来（出现 01:00、23:00 这种起点），一旦混进夜睡，
 * 「白天睡了多久」就会凭空变成几小时。窗口外的段直接丢弃。
 *
 * <p>午睡不计入夜睡的 {@code asleepMinutes}，两者分开看。
 *
 * @param ofDate   午睡所在的日历日（第一个午睡段的日期）
 * @param minutes  午睡总分钟数
 * @param segments 每段午睡的起止
 */
public record SleepNap(
        LocalDate ofDate,
        int minutes,
        List<SleepInterval> segments) {
}
