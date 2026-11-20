package org.example.sporthealth.domain;

import java.util.List;

/**
 * HRV（RMSSD）的长期趋势。
 *
 * <p><b>口径只有睡眠</b>：{@code basis} 恒为 {@code sleep}，每夜的值取睡眠会话窗口内的均值。
 * 不用全天均值 —— 实测日间均值 68.7 ms、夜间 48.2 ms，差 20 ms，比任何真实趋势都大；
 * 而全天均值还取决于「那天白天测了几条」，样本结构一变，趋势就变成采样方式变化造成的假象。
 *
 * <p>{@code direction} 是数值涨跌（up/down/flat），{@code assessment} 才是好坏
 * —— HRV 上升是 improving，两者必须分开，否则没法跟静息心率那种「降了才好」的指标并列。
 *
 * @param unit          固定 {@code ms}
 * @param higherIsBetter 恒为 {@code true}
 * @param basis         口径，恒为 {@code sleep}（睡眠会话窗口）
 * @param periods       周期序列，按时间升序
 * @param nights        区间内参与统计的可用夜数
 * @param average       区间睡眠 HRV 均值
 * @param min           区间最低的一夜
 * @param max           区间最高的一夜
 * @param latest        最后一个有数据的周期
 * @param change        末周期 − 首周期（两个都是均值）
 * @param changePercent 相对首周期的百分比变化
 * @param slopePerPeriod 最小二乘斜率（每个周期变化多少 ms）
 * @param r2            拟合优度，{@code <0.3} 说明波动大于趋势
 * @param direction     数值方向：up / down / flat / unknown
 * @param assessment    好坏：improving / worsening / flat / unknown
 * @param periodsUsed   参与拟合的周期数
 * @param periodsExcluded 被排除的周期数（截断或该周期没有可用夜）
 * @param excludedNights  因样本不足而未采用的夜数
 * @param notes         口径提示
 */
public record HrvTrend(
        String unit,
        boolean higherIsBetter,
        String basis,
        List<HrvPeriod> periods,
        int nights,
        Double average,
        Double min,
        Double max,
        HrvPeriod latest,
        Double change,
        Double changePercent,
        Double slopePerPeriod,
        Double r2,
        String direction,
        String assessment,
        int periodsUsed,
        int periodsExcluded,
        int excludedNights,
        List<String> notes) {
}
