package org.example.sporthealth.support;

import java.time.LocalDate;

/**
 * 闭区间日期范围（含首尾）。
 *
 * @param from 开始日期（含）
 * @param to   结束日期（含）
 */
public record DateRange(LocalDate from, LocalDate to) {

    /** 自然日数，含首尾；单日区间返回 1。 */
    public int dayCount() {
        return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    }

    /** 日期是否落在区间内；null 一律返回 false。 */
    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** 面向上下的展示文本，例如 {@code 2026-10-25 ~ 2026-11-03}。 */
    public String text() {
        return from + " ~ " + to;
    }
}
