package org.example.sporthealth.weather;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * open-meteo {@code hourly} 段的解析结果：一条按小时对齐的时间轴 + 若干变量序列。
 *
 * <p>解析 {@code {"time": [...], "temperature_2m": [...]}} 这种列式结构，
 * 缺失变量、序列短于时间轴、元素为 null 都按「该小时无值」处理，不抛异常——
 * 接口对超出模型范围的小时就是返回 null，这属于正常情况。
 */
public final class HourlySeries {

    private final List<Instant> times;
    private final Map<String, List<Double>> values;

    private HourlySeries(List<Instant> times, Map<String, List<Double>> values) {
        this.times = times;
        this.values = values;
    }

    /**
     * @param hourly open-meteo 响应里的 {@code hourly} 节点，形如 {@code Map<变量名, List<Object>>}
     */
    @SuppressWarnings("unchecked")
    public static HourlySeries from(Map<String, Object> hourly) {
        if (hourly == null || hourly.isEmpty()) {
            return new HourlySeries(List.of(), Map.of());
        }
        Object rawTimes = hourly.get("time");
        if (!(rawTimes instanceof List<?> timeList)) {
            return new HourlySeries(List.of(), Map.of());
        }

        List<Instant> times = new ArrayList<>(timeList.size());
        for (Object raw : timeList) {
            times.add(parseTime(raw));
        }

        Map<String, List<Double>> values = new HashMap<>();
        for (Map.Entry<String, Object> entry : hourly.entrySet()) {
            if ("time".equals(entry.getKey()) || !(entry.getValue() instanceof List<?> list)) {
                continue;
            }
            List<Double> series = new ArrayList<>(list.size());
            for (Object element : list) {
                series.add(toDouble(element));
            }
            values.put(entry.getKey(), series);
        }

        // 时间解析失败的格子无法定位，必须把时间轴和所有变量序列在同一位置上一起剔除，
        // 否则两边下标会错位。也不能只留着 null 交给下层的二分查找（null 会让它漏掉正确下标），
        // 更不能用 List.copyOf——那会直接抛 NPE，把「响应格式不对」伪装成「网络失败」，白等一个冷却周期。
        List<Instant> validTimes = withoutUnparsable(times, values);
        return new HourlySeries(List.copyOf(validTimes), Map.copyOf(values));
    }

    /**
     * 剔除时间为 null 的格子，其余各变量序列同步删掉对应下标。
     *
     * @return 压缩后的时间轴；没有无效格子时原样返回
     */
    private static List<Instant> withoutUnparsable(List<Instant> times, Map<String, List<Double>> values) {
        if (!times.contains(null)) {
            return times;
        }
        List<Integer> keep = new ArrayList<>(times.size());
        for (int index = 0; index < times.size(); index++) {
            if (times.get(index) != null) {
                keep.add(index);
            }
        }
        values.replaceAll((ignored, series) -> {
            List<Double> compacted = new ArrayList<>(keep.size());
            for (int index : keep) {
                compacted.add(index < series.size() ? series.get(index) : null);
            }
            return compacted;
        });
        List<Instant> compactedTimes = new ArrayList<>(keep.size());
        for (int index : keep) {
            compactedTimes.add(times.get(index));
        }
        return compactedTimes;
    }

    public boolean isEmpty() {
        return times.isEmpty();
    }

    public List<Instant> times() {
        return times;
    }

    /** 某变量在索引 {@code index} 处的值；越界、变量不存在、值为 null 都返回 null。 */
    public Double value(String variable, int index) {
        List<Double> series = values.get(variable);
        if (series == null || index < 0 || index >= series.size()) {
            return null;
        }
        return series.get(index);
    }

    /**
     * open-meteo 在 {@code timezone=UTC} 下返回 {@code 2026-11-02T22:00} 这种不带时区的形式，
     * 按 UTC 解释。个别情况下可能带 {@code Z}，两种都接受。
     */
    private static Instant parseTime(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return text.endsWith("Z")
                    ? Instant.parse(text)
                    : Instant.parse(text + ":00Z");
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static Double toDouble(Object element) {
        return switch (element) {
            case null -> null;
            case Number number -> number.doubleValue();
            case String text -> parseDouble(text);
            default -> null;
        };
    }

    private static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
