package org.example.sporthealth.domain;

import java.util.Comparator;
import java.util.Locale;

/**
 * 跑步列表的过滤与排序条件。所有字段为空表示不过滤，{@code sortBy} 默认为 {@code date}。
 *
 * <p>排序值：{@code date}（默认，新的在前）、{@code distance}、{@code duration}、
 * {@code pace}（快的在前）、{@code climb}。无法识别的值按 {@code date} 处理。
 *
 * @param sport                运动类型，精确匹配 {@link RunActivity#sport()}
 * @param minDistanceKm        最小距离（公里，含）
 * @param maxDistanceKm        最大距离（公里，含）
 * @param minPaceSecondsPerKm  配速下限（秒/公里，含），排除比它更快的记录
 * @param maxPaceSecondsPerKm  配速上限（秒/公里，含），排除比它更慢的记录
 * @param weekday              星期几，1=周一 … 7=周日（ISO 口径）
 * @param sortBy               排序字段
 */
public record RunFilter(
        String sport,
        Double minDistanceKm,
        Double maxDistanceKm,
        Double minPaceSecondsPerKm,
        Double maxPaceSecondsPerKm,
        Integer weekday,
        String sortBy) {

    /** 不过滤、按日期倒序。 */
    public static final RunFilter ALL = new RunFilter(null, null, null, null, null, null, "date");

    public RunFilter {
        sport = sport == null || sport.isBlank() ? null : sport.trim();
        sortBy = normalizeSort(sortBy);
        weekday = weekday == null || weekday < 1 || weekday > 7 ? null : weekday;
        if (minDistanceKm != null && maxDistanceKm != null && minDistanceKm > maxDistanceKm) {
            throw new IllegalArgumentException(
                    "最小距离 " + minDistanceKm + " 不能大于最大距离 " + maxDistanceKm);
        }
        if (minPaceSecondsPerKm != null && maxPaceSecondsPerKm != null
                && minPaceSecondsPerKm > maxPaceSecondsPerKm) {
            throw new IllegalArgumentException(
                    "配速下限 " + minPaceSecondsPerKm + " 不能大于配速上限 " + maxPaceSecondsPerKm);
        }
    }

    /** 记录是否满足全部条件；配速条件在记录缺配速时视为不匹配。 */
    public boolean matches(RunActivity run) {
        if (run == null) {
            return false;
        }
        if (sport != null && !sport.equalsIgnoreCase(run.sport())) {
            return false;
        }
        double distanceKm = run.distanceMeters() / 1000d;
        if (minDistanceKm != null && distanceKm < minDistanceKm) {
            return false;
        }
        if (maxDistanceKm != null && distanceKm > maxDistanceKm) {
            return false;
        }
        if (weekday != null && run.date().getDayOfWeek().getValue() != weekday) {
            return false;
        }
        if (minPaceSecondsPerKm != null || maxPaceSecondsPerKm != null) {
            Double pace = run.avgPaceSecondsPerKm();
            if (pace == null) {
                return false;
            }
            if (minPaceSecondsPerKm != null && pace < minPaceSecondsPerKm) {
                return false;
            }
            if (maxPaceSecondsPerKm != null && pace > maxPaceSecondsPerKm) {
                return false;
            }
        }
        return true;
    }

    /** 对应的比较器；缺值的记录统一排在最后。 */
    public Comparator<RunActivity> comparator() {
        return switch (sortBy) {
            case "distance" -> Comparator.comparingDouble(RunActivity::distanceMeters).reversed();
            case "duration" -> Comparator.comparingDouble(RunActivity::durationSeconds).reversed();
            case "pace" -> Comparator.comparing(
                    RunActivity::avgPaceSecondsPerKm, Comparator.nullsLast(Comparator.naturalOrder()));
            case "climb" -> Comparator.comparing(
                    RunActivity::climbMeters, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(RunActivity::startTime).reversed();
        };
    }

    /** 是否带有任意过滤条件。 */
    public boolean isFiltered() {
        return sport != null || minDistanceKm != null || maxDistanceKm != null
                || minPaceSecondsPerKm != null || maxPaceSecondsPerKm != null || weekday != null;
    }

    /** 面向模型的过滤条件描述，便于回显“为什么只剩这些记录”。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (sport != null) {
            text.append("运动类型=").append(sport).append(' ');
        }
        if (minDistanceKm != null) {
            text.append("距离≥").append(minDistanceKm).append("km ");
        }
        if (maxDistanceKm != null) {
            text.append("距离≤").append(maxDistanceKm).append("km ");
        }
        if (minPaceSecondsPerKm != null) {
            text.append("配速≥").append(minPaceSecondsPerKm).append("s/km ");
        }
        if (maxPaceSecondsPerKm != null) {
            text.append("配速≤").append(maxPaceSecondsPerKm).append("s/km ");
        }
        if (weekday != null) {
            text.append("星期=").append(weekday).append(' ');
        }
        return text.toString().trim();
    }

    private static String normalizeSort(String value) {
        if (value == null || value.isBlank()) {
            return "date";
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "distance", "duration", "pace", "climb" -> key;
            default -> "date";
        };
    }
}
