package org.example.sporthealth.domain;

/**
 * 趋势里的一个「周期 + 数值」对，用于首末、最好最差这类单点引用。
 *
 * @param period 周期编号
 * @param value  该周期的主指标值；该周期缺这个指标时为 {@code null}
 */
public record SleepTrendValue(String period, Double value) {
}
