package org.example.sporthealth.domain;

/**
 * 整个区间的趋势结论，只针对 {@code metric} 指定的那一个指标。
 *
 * <p>为什么不用「首末相减」代替斜率：首末两个点各带一次测量的噪声，
 * 只要最后一个周期恰好低了 40 分钟，就会得出「在变差」的结论。
 * 这里用最小二乘对全部周期拟合，{@code slopePerPeriod} 是每个周期平均变化多少；
 * {@code change} 仍然给出「末减首」，两者不一致时正好说明数据在波动而不是单调。
 *
 * <p>{@code direction} 用<b>拟合出来的区间总变化</b>（{@code slopePerPeriod × (n-1)}）
 * 与最小有意义变化比较，而不是直接比每周期斜率：月粒度的 5 分钟/月等于一年 60 分钟，
 * 按每周期比会把明显的一年下滑判成「平稳」。{@code r2} 给出线性程度——
 * 方向是「往哪走」，r2 才回答「这条线有多可信」。
 *
 * @param metric            主指标标识，如 {@code asleep}
 * @param unit              斜率与数值的单位，如 {@code minutes}
 * @param higherIsBetter    该指标是否越高越好
 * @param periodsUsed       参与拟合的周期数
 * @param periodsExcluded   被排除的周期数（首/末被查询区间截断，或该指标在这些周期没有值）
 * @param first             参与拟合的第一个周期
 * @param last              参与拟合的最后一个周期
 * @param change            {@code last - first}，无值时为 {@code null}
 * @param changePercent     变化相对 {@code first} 的百分比
 * @param slopePerPeriod    最小二乘斜率：每个周期平均变化多少（单位见 {@code unit}）
 * @param r2                拟合优度（0~1），越接近 1 越像一条直线；各周期数值完全相同时为 {@code null}
 * @param direction         {@code up} / {@code down} / {@code flat}
 * @param assessment        {@code improving} / {@code worsening} / {@code flat}
 * @param best              主指标最好的周期（按 {@code higherIsBetter} 取最大或最小）
 * @param worst             主指标最差的周期
 * @param method            拟合方式的简短说明
 */
public record SleepTrend(
        String metric,
        String unit,
        boolean higherIsBetter,
        int periodsUsed,
        int periodsExcluded,
        SleepTrendValue first,
        SleepTrendValue last,
        Double change,
        Double changePercent,
        Double slopePerPeriod,
        Double r2,
        String direction,
        String assessment,
        SleepTrendValue best,
        SleepTrendValue worst,
        String method) {
}
