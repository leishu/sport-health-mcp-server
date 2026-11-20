package org.example.sporthealth.domain;

/**
 * 体脂秤上的一套「用户配置」（身高 + 性别），也就是一个可以独立算趋势的人。
 *
 * <p>同一台秤会存多个用户，导出后全部落在 {@code body_measurement} 一张表里。
 * 实测 308 条分 7 组：186/男 141 条、168/女 132 条、171/女 25 条、193/男 3 条，
 * 另有 7 条没记性别（其中 1 条连身高都没有）。**不做这个分组就直接算体重均值，
 * 得到的是几个人混在一起的平均，毫无意义**。
 *
 * @param gender        {@code male} / {@code female} / {@code unknown}（库里 gender 为 NULL）
 * @param heightCm      身高（厘米）；库里没记身高时为 {@code null}
 * @param measurements  这套配置的测量条数
 * @param bodyComposition 其中带体成分（体脂率等）的条数，只称重的记录不算
 * @param label         给人看的名字，如 {@code 186cm · 男}
 */
public record BodyProfile(
        String gender,
        Integer heightCm,
        int measurements,
        int bodyComposition,
        String firstDate,
        String lastDate,
        double weightMin,
        double weightMax,
        String label) {
}
