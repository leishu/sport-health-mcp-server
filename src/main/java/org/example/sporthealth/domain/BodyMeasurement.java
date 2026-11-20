package org.example.sporthealth.domain;

/**
 * 一条体脂秤测量。
 *
 * <p>{@code measuredAt} 拆成 {@code date} 与 {@code time} 两个字段：称重常常一天好几次
 * （实测同一天 05:08 和 05:09 各一条），只给日期会让明细看起来像重复记录。
 *
 * <p>体成分字段（{@code bmi} 之后）只有 {@code has_body_composition=1} 的记录才有，
 * 只称重的记录这些字段全是 {@code null} —— 缺字段表示「这次没测体成分」，不是 0。
 *
 * @param bodyFatKg 体重脂肪量。**142 条有体脂率的记录里只有 129 条带这个字段**，
 *                  剩下 13 条虽有体脂率却没换算好的公斤数；这里不做换算，
 *                  缺失就是 {@code null}（口径见 {@code get_body_composition} 的 notes）。
 */
public record BodyMeasurement(
        String date,
        String time,
        double weightKg,
        Double bmi,
        Double bodyFatPercent,
        Double bodyFatKg,
        Double muscleMassKg,
        Double proteinPercent,
        Double bodyWaterPercent,
        Double boneSaltKg,
        Double visceralFatLevel,
        Double waistHipRatio,
        Double basalMetabolismKcal,
        Integer bodyAge,
        Double bodyScore,
        Integer heartRate) {
}
