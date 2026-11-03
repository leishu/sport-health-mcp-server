package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 区间内的个人最佳，每个字段都直接指向一条完整记录，便于工具层复用统一的返回结构。
 *
 * <p>区间内没有有效配速时 {@link #fastestPace} 为 null；没有记录时所有字段均为 null。
 *
 * @param from            区间开始日期
 * @param to              区间结束日期
 * @param runCount        参与统计的记录条数
 * @param longestDistance 距离最长的一次
 * @param fastestPace     平均配速最快的一次
 * @param longestDuration 时长最长的一次
 * @param mostClimb       累计爬升最大的一次；所有记录都没有爬升数据时为 null
 */
public record PersonalBests(
        LocalDate from,
        LocalDate to,
        int runCount,
        RunActivity longestDistance,
        RunActivity fastestPace,
        RunActivity longestDuration,
        RunActivity mostClimb) {
}
