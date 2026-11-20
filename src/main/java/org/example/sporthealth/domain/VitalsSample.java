package org.example.sporthealth.domain;

/**
 * 一个稀疏单点测量：时刻 + 数值。
 *
 * <p>压力与血氧都用它 —— 两者在 {@code health_metric} 里都是「隔一段测一次」的点，
 * 不像心率那样是连续曲线，没必要各造一个 record。
 *
 * @param time  本地时刻 {@code HH:mm}
 * @param value 测量值（压力为 0~100 分，血氧为百分比）
 */
public record VitalsSample(String time, double value) {
}
