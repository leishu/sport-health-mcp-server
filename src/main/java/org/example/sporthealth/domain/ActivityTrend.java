package org.example.sporthealth.domain;

/**
 * 单个活动指标「最近 7 天 vs 前 7 天」的对比。
 *
 * <p>两个窗口都锚在区间<b>最后一个有记录的日子</b>，各取 7 天：{@code recent} 是最后 7 天，
 * {@code previous} 是它之前的 7 天。用 7 天而不用逐日：步数逐日的波动主要来自「今天有没有跑步」，
 * 日粒度看趋势只会得到锯齿。
 *
 * <p>{@code direction} 按变化百分比判 ±5%：低于这个幅度更像正常波动，
 * 报成「变多了」反而误导。两个窗口里任一没有记录时 {@code change} 为 null、{@code direction}
 * 为 {@code unknown} —— 那是「看不出」，不是「没变化」。
 *
 * @param unit            单位（步 / 公里 / 分钟 / 次）
 * @param recentDays      最近窗口里有记录的天数
 * @param previousDays    前一个窗口里有记录的天数
 * @param recent          最近窗口的日均值
 * @param previous        前一个窗口的日均值
 * @param changePercent   相对变化百分比（正数为增加），无法比较时为 null
 * @param direction       {@code up} / {@code down} / {@code flat} / {@code unknown}
 */
public record ActivityTrend(
        String unit,
        int recentDays,
        int previousDays,
        Double recent,
        Double previous,
        Double changePercent,
        String direction) {
}
