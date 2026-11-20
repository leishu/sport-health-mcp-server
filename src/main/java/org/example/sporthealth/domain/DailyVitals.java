package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一天的体征快照（{@code daily_vitals} 一行）。
 *
 * <p><b>一天的字段不一定同时有值</b>，四类指标的可用起点各不相同：
 * <ul>
 *   <li>静息心率与全天心率：2025-11-08 起连续 368 天；</li>
 *   <li>{@code hrvRmssd} 与 {@code stressAverage}：2026-06-12 起才有，更早的行是 null；</li>
 *   <li>{@code vo2max}：只有 104 天有值，且 2025-09 到 2026-11 之间整段断档。</li>
 * </ul>
 * null 一律是「当天没测到」，不是 0。
 *
 * <p>同一个字段的聚合口径也不同，来自派生表（{@code DerivedTables.DAILY_VITALS_SQL}），
 * 调用方不要自己再算一遍：静息心率取当天多条里的<b>最小值</b>（华为当日静息心率就是睡眠期
 * 最低的稳定心率），HRV 与压力取当天采样的<b>均值</b>，VO2max 取当天<b>最大值</b>。
 *
 * @param date              日期
 * @param restingHeartRate  静息心率（bpm）
 * @param hrvRmssd          HRV，RMSSD（毫秒）
 * @param averageHeartRate  全天自动采样心率均值（含睡眠段，所以比「日间平均心率」低不少）
 * @param minHeartRate      全天采样最低心率，多为睡眠期
 * @param maxHeartRate      全天采样最高心率；这是采样峰值，不等于实验室测的最大心率
 * @param heartRateSamples  当天自动采样条数；0 表示这天只有别的指标、没有心率
 * @param stressAverage     压力均值（华为 0~100 分，越高越紧张）
 * @param stressMax         压力峰值（0~100 分）
 * @param vo2max            最大摄氧量（ml/kg/min）
 */
public record DailyVitals(
        LocalDate date,
        Integer restingHeartRate,
        Double hrvRmssd,
        Double averageHeartRate,
        Integer minHeartRate,
        Integer maxHeartRate,
        int heartRateSamples,
        Double stressAverage,
        Integer stressMax,
        Double vo2max) {

    /**
     * 当天是否真有自动心率采样。
     *
     * <p>不是 {@code isXxx()} 形式，所以不会被 Jackson 当成属性写进返回体
     * —— record 上的 {@code is} / {@code get} 前缀方法会被序列化成多余字段。
     */
    public boolean sampledHeartRate() {
        return heartRateSamples > 0;
    }
}
