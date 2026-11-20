package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 一晚睡眠的完整详情：分期时间线 + 醒来时刻 + 睡眠碎片 + 报告指标。
 *
 * <p>与 {@link SleepNight}（汇总视图）的区别只在于「加了多少细节」，同一件事的口径完全一致：
 * {@code timeInBedMinutes} / {@code asleepMinutes} / {@code awakeMinutes} / {@code wakeCount}
 * 与 {@code get_sleep_summary} 给出的数字一定相同，detail 不会偷偷换一套算法。
 *
 * <p>三个时间口径要分清：
 * <ul>
 *   <li>{@code bedtime}/{@code waketime}：会话（或报告）的窗口，含躺下没睡着的时间；</li>
 *   <li>{@code report.bedTime}/{@code fallAsleepTime}/{@code risingTime}：报告自己记的上床/睡着/起床；</li>
 *   <li>{@code fragments.items}：设备认定的连续睡眠块，块之间的 {@code gaps} 才是设备口径的清醒。</li>
 * </ul>
 *
 * @param date                 醒来日期
 * @param bedtime              窗口开始（{@code yyyy-MM-dd HH:mm:ss}）
 * @param waketime             窗口结束
 * @param timeInBedMinutes     卧床时长
 * @param asleepMinutes        睡着时长
 * @param awakeMinutes         夜间清醒（WASO，不含入睡前与起床前）
 * @param wakeCount            醒来次数（分期口径：连续清醒 ≥2 分钟才算）
 * @param sleepLatencyMinutes  入睡潜伏期，只有报告有
 * @param efficiencyPercent    睡眠效率（%），只有报告有
 * @param score                睡眠评分，只有报告有
 * @param hrvMs                平均 HRV（毫秒），只有报告有
 * @param spo2Percent          平均血氧（%），只有报告且设备上报了才有
 * @param stages               各阶段合计与占比
 * @param timeline             时间线：相邻同阶段的分钟已合并
 * @param timelineSegments     合并后的段数（{@code timeline} 被截断时是完整段数）
 * @param timelineTruncated    时间线是否超长被截断
 * @param wakeEpisodes         夜间醒来的每一段（分期口径，含首次与末次）
 * @param fragments            睡眠碎片（仅 2026-06-12 之后有）
 * @param report               报告补充指标（心率/呼吸/打鼾/上床时刻），只有报告有
 * @param daytimeNap           入睡前的白天午睡；当天没午睡时为 null
 * @param sources              本行的数据来源：stages / report / fragments
 */
public record SleepDetailNight(
        LocalDate date,
        String bedtime,
        String waketime,
        int timeInBedMinutes,
        int asleepMinutes,
        int awakeMinutes,
        Integer wakeCount,
        Integer sleepLatencyMinutes,
        Double efficiencyPercent,
        Double score,
        Double hrvMs,
        Double spo2Percent,
        SleepStageTotals stages,
        List<SleepStageSegment> timeline,
        int timelineSegments,
        boolean timelineTruncated,
        List<SleepStageSegment> wakeEpisodes,
        SleepFragments fragments,
        SleepReportExtras report,
        SleepNap daytimeNap,
        List<String> sources) {
}
