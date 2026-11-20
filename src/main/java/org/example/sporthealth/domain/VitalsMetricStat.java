package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 单个体征指标在查询区间内的统计与变化方向。
 *
 * <p>三个「对比值」回答三个不同的问题，别混着用：
 * <ul>
 *   <li>{@code baseline}：区间末端往前 28 天的均值（不受查询区间限制，始终取库里真实数据），
 *       回答「现在的水平相对最近一个月怎么样」；</li>
 *   <li>{@code deltaVsBaseline}：最近 7 天均值 − {@code baseline}，是短期偏离；</li>
 *   <li>{@code change}：末 7 天均值 − 首 7 天均值，是区间内的走向。</li>
 * </ul>
 *
 * <p>{@code direction} 是数值方向（up/down/flat），{@code assessment} 是价值方向
 * （improving/worsening/flat/unknown）。静息心率下降是 improving，HRV 下降是 worsening
 * —— 必须看 {@code higherIsBetter}，不能只看 up/down。
 *
 * <p>区间不足 14 天、或该指标有值的天数不足 7 天时，{@code change} / {@code direction} /
 * {@code assessment} 都是 {@code "unknown"} 与 null：那不是「没变化」，是「看不出变化」。
 *
 * @param unit             单位
 * @param higherIsBetter   数值越高越好（HRV、VO2max 为 true；静息心率、压力为 false）
 * @param count            区间内该指标有值的天数
 * @param average          区间均值
 * @param min              区间最小值
 * @param max              区间最大值
 * @param latest           区间内最后一个有值的值
 * @param latestDate       该值对应的日期
 * @param baseline         区间末端往前 28 天的均值
 * @param deltaVsBaseline  最近 7 天均值 − baseline
 * @param change           末 7 天均值 − 首 7 天均值，无法判断时为 null
 * @param direction        {@code up} / {@code down} / {@code flat} / {@code unknown}
 * @param assessment       {@code improving} / {@code worsening} / {@code flat} / {@code unknown}
 */
public record VitalsMetricStat(
        String unit,
        boolean higherIsBetter,
        int count,
        Double average,
        Double min,
        Double max,
        Double latest,
        LocalDate latestDate,
        Double baseline,
        Double deltaVsBaseline,
        Double change,
        String direction,
        String assessment) {
}
