package org.example.sporthealth.domain;

/**
 * 压力曲线上的一个时间桶。
 *
 * <p>压力是「隔 30 分钟测一次」的稀疏点，不是连续曲线，所以桶里给的是均值与极值，不是采样点。
 *
 * @param bucket   桶的起始时刻 {@code HH:mm}
 * @param samples  桶内样本数
 * @param average  桶内均值（0~100，一位小数）
 * @param max      桶内最高
 * @param level    桶均值的档位：relaxed / normal / moderate / high
 */
public record StressBucket(String bucket, int samples, Double average, int max, String level) {
}
