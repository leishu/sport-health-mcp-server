package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 趋势图上的一个点，也就是一个周或一个月。
 *
 * <p>三个计数字段各管一件事，混在一起看会误判：
 * <ul>
 *   <li>{@code nights}：实际有睡眠记录的夜晚数；</li>
 *   <li>{@code expectedNights}：这个周期落在查询区间内的天数（区间从周三开始时，那一周是 5 天）；</li>
 *   <li>{@code complete}：周期是否被查询区间完整覆盖。为 {@code false} 表示首/末周期被截断，
 *       它的平均值天然偏低（少几个夜晚），趋势拟合会把它排除。</li>
 * </ul>
 * {@code nights < expectedNights} 是另一回事：区间覆盖了，但设备那几天没记录。
 *
 * @param period         周期编号，周为 {@code 2026-W46}，月为 {@code 2026-11}
 * @param from           周期本身的第一天（周一或 1 号），不是「有数据的第一天」
 * @param to             周期本身的最后一天（周日或月末）
 * @param nights         实际有睡眠记录的夜晚数
 * @param expectedNights 周期落在查询区间内的天数
 * @param complete       周期是否被查询区间完整覆盖
 * @param asleepText     平均睡着时长的可读写法，例如 {@code 6h 52m}
 * @param values         本周期的各项指标
 * @param deltas         与上一周期的差值
 * @param reportedNights 其中有官方报告（评分/效率/HRV/潜伏期可用）的夜晚数
 * @param spo2Nights     其中真有血氧值的夜晚数
 */
public record SleepTrendPoint(
        String period,
        LocalDate from,
        LocalDate to,
        int nights,
        int expectedNights,
        boolean complete,
        String asleepText,
        SleepTrendValues values,
        SleepTrendDeltas deltas,
        int reportedNights,
        int spo2Nights) {
}
