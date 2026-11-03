package org.example.sporthealth.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 宽松的日期解析工具，供 MCP 工具参数使用。
 *
 * <p>优先接受标准格式，同时兼容模型或用户常见的几种写法：
 * <ul>
 *   <li>日期：{@code yyyy-MM-dd}、{@code yyyy/M/d}、{@code yyyyMMdd}</li>
 *   <li>时间：{@code yyyy-MM-ddTHH:mm:ss}、{@code yyyy-MM-dd HH:mm[:ss]}、{@code yyyy/M/d H:mm[:ss]}</li>
 * </ul>
 * 解析失败统一抛出 {@link IllegalArgumentException}，由 MCP 层转换为工具错误。
 */
public final class DateParser {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private DateParser() {
    }

    /**
     * 解析日期；带时间部分时只取日期。
     *
     * @param value 非空字符串
     * @return 本地日期
     * @throws IllegalArgumentException 为空或所有格式都不匹配
     */
    public static LocalDate parseDate(String value) {
        String text = requireText(value);
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new IllegalArgumentException("无法解析日期: " + text + "，请使用 yyyy-MM-dd 格式");
    }

    /**
     * 解析时间；只有日期时补成当天 00:00。
     *
     * @param value 非空字符串
     * @return 本地时间
     * @throws IllegalArgumentException 为空或所有格式都不匹配
     */
    public static LocalDateTime parseDateTime(String value) {
        String text = requireText(value);
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new IllegalArgumentException("无法解析时间: " + text + "，请使用 yyyy-MM-dd HH:mm:ss 格式");
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("日期不能为空");
        }
        return value.trim();
    }
}
