package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 全天自动心率的区间概览。
 *
 * <p>与 {@link VitalsMetricStat} 分开，是因为这里三个数不是「同一种量的统计」：
 * {@code average} 是先把每天的全天均值再平均（含睡眠段，所以整体偏低），
 * 而 {@code lowest} / {@code highest} 是区间内采到过的极值（睡眠最低点、运动峰值）。
 *
 * <p>{@code highest} 不是实验室测的最大心率，只是一天中自动采样到的最大值；
 * 拿它当 HRmax 会低估，算区间时只能用 {@code get_run_detail} 里的跑步峰值。
 *
 * @param unit                单位（bpm）
 * @param days                区间内有自动心率采样的天数
 * @param average             各天全天均值再取平均
 * @param lowest              区间内采到的最低心率
 * @param highest             区间内采到的最高心率
 * @param latestDailyAverage  区间内最后一天的全天均值
 * @param latestDate          该值对应的日期
 * @param change              末 7 天均值 − 首 7 天均值，无法判断时为 null
 * @param direction           {@code up} / {@code down} / {@code flat} / {@code unknown}
 * @param assessment          {@code improving} / {@code worsening} / {@code flat} / {@code unknown}
 */
public record HeartRateStat(
        String unit,
        int days,
        Double average,
        Integer lowest,
        Integer highest,
        Double latestDailyAverage,
        LocalDate latestDate,
        Double change,
        String direction,
        String assessment) {
}
