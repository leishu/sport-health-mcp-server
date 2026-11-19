package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 一次跑步的跑姿与步幅汇总。
 *
 * <p>两类字段来源不同，缺一样不影响另一样：
 * <ul>
 *   <li>{@code groundContactTimeMs} / {@code verticalOscillationCm} / {@code groundBalancePercent}
 *       来自华为 {@code summaryData} 的跑姿块，<b>只有连接过跑步动态传感器</b>（心率带/跑姿传感器）
 *       的记录才有；数据集里 154 次跑步中 63 次有。</li>
 *   <li>{@code strideLengthMeters} 没有直接字段，用「总距离 ÷ 总步数」折算，所以只要有华为导出
 *       的步数就能算。{@code cadenceSpm} 是华为给的平均步频。</li>
 * </ul>
 *
 * @param runId                    记录 id
 * @param date                     本地日期
 * @param distanceKm               距离（公里）
 * @param paceSecondsPerKm         平均配速（秒/公里）
 * @param paceText                 平均配速文本，例如 5'46"/km
 * @param cadenceSpm               平均步频（步/分），没有时为 null
 * @param strideLengthMeters       平均步幅（米），缺步数时为 null
 * @param groundContactTimeMs      平均触地时间（毫秒），没有跑姿数据时为 null
 * @param verticalOscillationCm    平均垂直振幅（厘米），没有跑姿数据时为 null
 * @param groundBalancePercent     触地平衡（%），50 表示左右均衡，没有跑姿数据时为 null
 * @param sampleCount              跑姿原始采样行数（每 5 秒一个），批量汇总时可能为 0
 */
public record RunningDynamics(
        String runId,
        LocalDate date,
        double distanceKm,
        Double paceSecondsPerKm,
        String paceText,
        Integer cadenceSpm,
        Double strideLengthMeters,
        Double groundContactTimeMs,
        Double verticalOscillationCm,
        Double groundBalancePercent,
        int sampleCount) {

    /** 是否有跑姿三项（触地时间/垂直振幅/左右平衡）中的任意一项。 */
    public boolean hasPosture() {
        return groundContactTimeMs != null || verticalOscillationCm != null || groundBalancePercent != null;
    }

    /** 是否有任何一项可用的跑姿或步幅指标。 */
    public boolean hasAnyMetric() {
        return hasPosture() || strideLengthMeters != null || cadenceSpm != null;
    }
}
