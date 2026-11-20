package org.example.sporthealth.domain;

/**
 * 睡眠时间线上的一段分期（相邻同阶段的分钟已经合并）。
 *
 * <p>{@code stage} 只会有四个值：{@code deep}（深睡）、{@code light}（浅睡）、
 * {@code rem}（快速眼动）、{@code wake}（清醒）。午睡不在这里。
 *
 * @param stage   分期
 * @param start   开始时刻
 * @param end     结束时刻
 * @param minutes 时长（分钟）
 */
public record SleepStageSegment(
        String stage,
        String start,
        String end,
        int minutes) {
}
