package org.example.sporthealth.domain;

/**
 * 单个体成分指标在区间内的变化。
 *
 * <p>{@code change} 是「最后一条 − 第一条」（按测量时间排序），{@code changePercent}
 * 是它相对第一条的百分比。体重的变化要按公斤读、体脂率要按百分点读，所以两个都给，
 * 不在这里判断好坏 —— 「体重降 2 公斤」在减脂期是好事、在备赛期可能不是。
 *
 * @param count 参与统计的测量条数（该指标有值的条数，不是区间里的总条数）
 */
public record BodyMetricTrend(
        String unit,
        Double first,
        Double last,
        Double change,
        Double changePercent,
        Double min,
        Double max,
        Double average,
        int count) {
}
