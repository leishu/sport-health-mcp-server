package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一夜的 HRV（RMSSD，毫秒）。
 *
 * <p>{@code date} 是<b>起床日</b>（取自 {@code sleep_session.sleep_date}），不是样本所在的日历日
 * —— 一夜睡眠跨越两个日历日，按日历日切会把同一夜劈成两半、各自还混着别的一夜的碎片。
 *
 * <p>三个均值口径并存，用途不同：
 * <ul>
 *   <li>{@code rmssd}：睡眠会话窗口内的均值，<b>主口径</b>，趋势只认它；</li>
 *   <li>{@code dayRmssd}：起床那天 06:00–22:00 的均值，对照用。日间样本少于 5 条时置
 *       {@code null} —— 单条样本就能把均值拉到 100 以上（实测 2026-11-10 只有一条 126 ms），
 *       那种数字比不给还糟；</li>
 *   <li>{@code wholeDayRmssd}：日历日的全天均值，就是 {@code daily_vitals.hrv_rmssd} 的口径，
 *       只用来跟那个工具对齐，<b>不能拿它判断趋势</b>。</li>
 * </ul>
 *
 * @param date           起床日
 * @param sleepStart     入睡时刻 {@code HH:mm}
 * @param sleepEnd       起床时刻 {@code HH:mm}
 * @param sleepSamples   睡眠窗口内采到的 HRV 样本数
 * @param rmssd          睡眠窗口内均值（一位小数）
 * @param daySamples     日间（06:00–22:00）样本数
 * @param dayRmssd       日间均值；有效样本不足时为 {@code null}
 * @param wholeDayRmssd  日历日全天均值（{@code daily_vitals} 口径）
 */
public record HrvNight(
        LocalDate date,
        String sleepStart,
        String sleepEnd,
        int sleepSamples,
        Double rmssd,
        int daySamples,
        Double dayRmssd,
        Double wholeDayRmssd) {

    /** 这一夜能不能用来算趋势：样本太少时均值不稳，宁可不用。 */
    public boolean reliable(int minSamples) {
        return rmssd != null && sleepSamples >= minSamples;
    }
}
