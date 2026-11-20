package org.example.sporthealth.domain;

/**
 * 查询区间内「多少天真有这类数据」。
 *
 * <p>分母永远是 {@code days}（区间自然日数），其余字段各自计数 —— 四个指标的覆盖率差得很远，
 * 拿一个总数当分母会把「HRV 只有半年数据」读成「数据质量差」。
 *
 * @param days                 区间自然日数（含首尾）
 * @param dailyRows            有 {@code daily_vitals} 行的天数
 * @param restingHeartRateDays 有静息心率的天数
 * @param hrvDays              有 HRV 的天数
 * @param stressDays           有压力的天数
 * @param vo2maxDays           有最大摄氧量的天数
 * @param heartRateDays        有自动心率采样（{@code heart_rate_samples > 0}）的天数
 */
public record VitalsCoverage(
        int days,
        int dailyRows,
        int restingHeartRateDays,
        int hrvDays,
        int stressDays,
        int vo2maxDays,
        int heartRateDays) {
}
