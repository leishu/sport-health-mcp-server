package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 连续打卡区间。
 *
 * @param days 连续天数
 * @param from 开始日期
 * @param to   结束日期
 */
public record Streak(int days, LocalDate from, LocalDate to) {

    /** 没有任何记录时的空区间。 */
    public static Streak empty() {
        return new Streak(0, null, null);
    }
}
