package org.example.sporthealth.domain;

/**
 * 一天自动心率的密度与极值概况。
 *
 * <p>字段里同时保留统计量和「数据密度」，因为这张表的采样密度会随设备策略变化：
 * 2025-11 那批是每分钟一条（一天 1400+ 点），2026-11 静息时拉长到 10 分钟一条（一天约 250 点）。
 * 只看 {@code samples} 会把采样策略的变化误读成「那天没戴表」，所以一定要看
 * {@code medianGapMinutes}。
 *
 * @param samples           采样点数（与 {@code daily_vitals.heart_rate_samples} 同源同值）
 * @param min               全天最低心率
 * @param max               全天最高心率
 * @param average           全天均值
 * @param firstSample       首个采样的时刻（{@code HH:mm}）
 * @param lastSample        最后一个采样的时刻（{@code HH:mm}）
 * @param spanMinutes       首个到末个采样跨越的分钟数
 * @param medianGapMinutes  采样间隔中位数；只有 1 个采样点时是 null
 * @param maxGapMinutes     最长一段没有采样的空档（分钟）
 */
public record HeartRateDayStats(
        int samples,
        int min,
        int max,
        double average,
        String firstSample,
        String lastSample,
        int spanMinutes,
        Integer medianGapMinutes,
        int maxGapMinutes) {
}
