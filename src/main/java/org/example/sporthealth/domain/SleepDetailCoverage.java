package org.example.sporthealth.domain;

/**
 * 睡眠详情的数据覆盖计数，用来说明「这些夜晚里到底有几种来源」。
 *
 * <p>详情由三份数据拼成，覆盖的时间段不同：分期全历史、报告 2026-06-13 起、
 * 碎片 2026-06-12 起。四个计数不一致是常态，不是异常。
 *
 * @param nights         返回的夜晚数
 * @param stageNights    其中有分期时间线的夜晚数
 * @param reportNights   其中有官方报告的夜晚数
 * @param fragmentNights 其中有睡眠碎片的夜晚数
 * @param note           需要提示的口径差异；没有时为 null
 */
public record SleepDetailCoverage(
        int nights,
        int stageNights,
        int reportNights,
        int fragmentNights,
        String note) {
}
