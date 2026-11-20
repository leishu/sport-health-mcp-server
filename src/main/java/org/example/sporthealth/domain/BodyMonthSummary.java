package org.example.sporthealth.domain;

/**
 * 按月的体成分概览，用来回答「这几个月体重怎么走的」。
 *
 * <p>{@code measurements} 与 {@code days} 分开给：同一台秤一天可以测好几次
 * （实测 141 条里 29 条与上一条相隔不到 1 天），只看条数会把「称得勤」误读成「数据多」。
 *
 * @param month               {@code yyyy-MM}
 * @param days                当月有测量的天数
 * @param weightAverage       当月所有测量点的体重均值（同一天多次会一起计入）
 * @param bodyFatAverage      当月体脂率均值；当月没有体成分记录时为 {@code null}
 */
public record BodyMonthSummary(
        String month,
        int measurements,
        int days,
        Double weightAverage,
        Double weightMin,
        Double weightMax,
        Double bodyFatAverage) {
}
