package org.example.sporthealth.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 睡眠时长的展示格式。
 *
 * <p>不用 {@code PaceFormat.clock()}：那个是给成绩用的 {@code h:mm:ss}，
 * 睡眠只关心到分钟，{@code 6:14:00} 这种写法既啰嗦又容易被读成「6 小时 14 分 0 秒」之外的东西。
 */
public final class SleepFormat {

    /** 与其它工具返回体一致的时间格式：{@code yyyy-MM-dd HH:mm:ss}，不带 ISO 的 {@code T}。 */
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private SleepFormat() {
    }

    /**
     * 分钟数转成 {@code 6h 14m} / {@code 48m} 这样的写法。
     *
     * @param minutes 分钟数，{@code null} 或负数返回 {@code null}（缺数据不写成 0）
     */
    public static String hoursAndMinutes(Integer minutes) {
        if (minutes == null || minutes < 0) {
            return null;
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return hours + "h " + rest + "m";
    }

    /** 入睡/起床时刻按项目约定格式化成字符串；{@code null} 保持 {@code null}。 */
    public static String dateTime(LocalDateTime value) {
        return value == null ? null : DATE_TIME_FORMAT.format(value);
    }
}
