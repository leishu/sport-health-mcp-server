package org.example.sporthealth.domain;

/**
 * 睡眠数据的来源覆盖情况，专治「把 null 当成 0」。
 *
 * <p>睡眠有两套独立来源，覆盖的时间段不一样：
 * <ul>
 *   <li>睡眠分期（{@code sleep_session} 与同批 {@code health_metric} 区间）覆盖全部历史，
 *       能算卧床/睡着时长与深睡/浅睡/快速眼动；</li>
 *   <li>官方睡眠报告（{@code sleep_report}）只有 2026-06-13 之后才有，评分、效率、HRV、
 *       血氧、入睡潜伏期都只在这里。</li>
 * </ul>
 * 所以「区间内 30 个夜晚」不等于「30 个夜晚都有评分」。这组计数把差值显式写出来：
 * {@code stageOnlyNights = nights - reportDays}，{@code nights - sessionDays} 则是连分期都没有的夜晚。
 *
 * @param nights          区间内统计到的夜晚数
 * @param sessionDays     其中有睡眠分期的夜晚数（能给出时长与深/浅/REM）
 * @param reportDays      其中有官方睡眠报告的夜晚数
 * @param stageOnlyNights 只有分期、没有报告的夜晚数——这些夜晚的评分类字段是 null
 * @param scoredNights    其中有睡眠评分的夜晚数（与 {@code reportDays} 通常相等）
 * @param spo2Nights      其中真正上报了血氧的夜晚数；设备常年只在个别夜晚给值，其余为 null
 * @param note            给调用方看的口径说明，没有需要注意的地方时为 null
 */
public record SleepCoverage(
        int nights,
        int sessionDays,
        int reportDays,
        int stageOnlyNights,
        int scoredNights,
        int spo2Nights,
        String note) {

    /** 有报告的夜晚占全部夜晚的百分比，无夜晚时为 null。 */
    public Double reportCoveragePercent() {
        return nights == 0 ? null : Math.round(reportDays * 1000d / nights) / 10d;
    }
}
