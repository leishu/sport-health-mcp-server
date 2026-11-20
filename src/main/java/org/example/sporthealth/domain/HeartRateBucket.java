package org.example.sporthealth.domain;

/**
 * 一个时间桶内的心率聚合，用来把上千个采样点压成可读的曲线。
 *
 * <p>{@code time} 是桶的起点（{@code HH:mm}）。{@code resolution=minute} 时每个桶就是一个采样点，
 * 此时 {@code min == max == average}、{@code samples == 1}。
 *
 * <p>只输出「有数据的桶」：中间那些没采样的分钟不会补 null，
 * 否则一天 288 个桶里一半是 null，曲线反而看不清。
 *
 * @param samples 桶内采样点数
 */
public record HeartRateBucket(String time, int min, double average, int max, int samples) {
}
