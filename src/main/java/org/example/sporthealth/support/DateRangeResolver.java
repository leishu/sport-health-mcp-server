package org.example.sporthealth.support;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * 把工具参数里的日期字符串解析成 {@link DateRange}。
 *
 * <p>两种语义：
 * <ul>
 *   <li>{@link #lastDaysOrRange}：只给一端时以“今天”为基准，适合实时场景；</li>
 *   <li>{@link #resolve}：两端都为空时优先用数据集自身的区间，避免默认 7 天窗口
 *       把历史记录挡在外面。</li>
 * </ul>
 * 任一情况都要求 {@code from <= to}，否则抛 {@link IllegalArgumentException}。
 */
@Component
public class DateRangeResolver {

    private static final int DEFAULT_DAYS = 7;

    private final Clock clock;

    public DateRangeResolver(Clock clock) {
        this.clock = clock;
    }

    /** 以今天为基准解析区间；缺省为最近 7 天（含今天）。 */
    public DateRange lastDaysOrRange(String startDate, String endDate) {
        LocalDate to = (endDate == null || endDate.isBlank())
                ? LocalDate.now(clock)
                : DateParser.parseDate(endDate);
        LocalDate from = (startDate == null || startDate.isBlank())
                ? to.minusDays(DEFAULT_DAYS - 1L)
                : DateParser.parseDate(startDate);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 不能晚于结束日期 " + to);
        }
        return new DateRange(from, to);
    }

    /** 未给日期时返回今天。 */
    public LocalDate parseOrToday(String date) {
        return (date == null || date.isBlank()) ? LocalDate.now(clock) : DateParser.parseDate(date);
    }

    /**
     * 解析用户给的日期区间；两端都为空时优先用数据自身的区间，避免默认窗口把历史数据挡在外面。
     *
     * @param startDate    开始日期，可为空
     * @param endDate      结束日期，可为空
     * @param fallbackFrom 数据集最早日期，可为 null
     * @param fallbackTo   数据集最晚日期，可为 null
     */
    public DateRange resolve(String startDate, String endDate, LocalDate fallbackFrom, LocalDate fallbackTo) {
        LocalDate to = (endDate == null || endDate.isBlank())
                ? (fallbackTo != null ? fallbackTo : LocalDate.now(clock))
                : DateParser.parseDate(endDate);
        LocalDate from = (startDate == null || startDate.isBlank())
                ? (fallbackFrom != null ? fallbackFrom : to.minusDays(DEFAULT_DAYS - 1L))
                : DateParser.parseDate(startDate);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 不能晚于结束日期 " + to);
        }
        return new DateRange(from, to);
    }
}
