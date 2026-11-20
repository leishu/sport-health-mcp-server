package org.example.sporthealth.domain;

import java.util.List;

/**
 * 睡眠碎片：设备把一夜睡眠切成的连续睡眠块（{@code sample_sequence} type=700008）。
 *
 * <p>这不是「醒来次数」的另一种写法，而是设备自己认定的一段段连续睡眠：
 * 块与块之间的间隙就是设备眼中的夜间清醒，比逐分钟分期拼出来的清醒段更细
 * （分期会把相邻 ≤1 分钟的清醒粘连，碎片则保留 1~2 分钟的空隙）。
 * 两种口径的醒来次数在约 1/3 的夜晚会差 1 次，差值都来自这些 1~2 分钟的空隙，
 * 所以两份都给出，不做「谁对谁错」的取舍。
 *
 * <p><b>覆盖</b>：这条数据只有 2026-06-12 之后才有。更早的夜晚 {@code available=false}，
 * 此时所有数值字段是 {@code null}、数组为空 —— 缺数据，不是 0。
 *
 * @param available       该夜是否有碎片数据
 * @param count           碎片块数；块数 - 1 大致就是夜里醒来的次数
 * @param totalMinutes    碎片块总时长（即设备认定的睡着时长）
 * @param longestMinutes  最长一块的分钟数；这一项最能反映「有没有睡整」
 * @param averageMinutes  平均块长（分钟）
 * @param items           每一块的起止，按时间升序
 * @param gaps            块与块之间的间隙（设备口径的清醒段），按时间升序
 * @param gapMinutes      间隙总分钟数，与 {@code awakeMinutes} 的 WASO 口径可对照
 * @param wakeGapCount    ≥2 分钟的间隙个数，与 {@code wakeCount} 的口径对齐后可直接比较
 * @param note            该夜的碎片口径说明；无异常时为 null
 */
public record SleepFragments(
        boolean available,
        Integer count,
        Integer totalMinutes,
        Integer longestMinutes,
        Double averageMinutes,
        List<SleepInterval> items,
        List<SleepInterval> gaps,
        Integer gapMinutes,
        Integer wakeGapCount,
        String note) {
}
