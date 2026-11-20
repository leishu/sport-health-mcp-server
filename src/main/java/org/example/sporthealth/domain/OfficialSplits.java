package org.example.sporthealth.domain;

import java.util.List;

/**
 * 一次跑步的华为官方分段配速，按段单位分为两套口径。
 *
 * <p>{@code km} 来自 {@code track_stream} 的 {@code pm2}（官方单公里配速），
 * {@code mile} 来自 {@code bpm2}（{@code b-p-m}，英制单英里配速，1 英里 = 1609.344 米）。
 * 两者是同一份采样换算到不同单位的两次切分，长度不同、段数不同，不是「公里 vs 最佳」的关系。
 *
 * <p>导出里没有对应流的记录（TCX 来源、室内跑）得到 {@link #usable()} 为 false 的空结果，
 * 调用方不用判空。
 *
 * @param runId                记录 id
 * @param unit                 段单位：{@code km} / {@code mile}
 * @param source               来源标记：{@code HUAWEI_PM2} / {@code HUAWEI_BPM2}
 * @param unitMeters           段单位长度（米）：1000 / 1609.344
 * @param coveredKm            分段累计覆盖的距离（公里），正常情况下等于该次运动距离
 * @param splits               分段列表，按终点距离升序，最后一段可能不满
 * @param fastest              最快的整段（没有整段时为 null）
 * @param slowest              最慢的整段（没有整段时为 null）
 * @param avgPaceSecondsPerUnit 按距离加权的整体配速（秒/段单位）
 * @param avgPaceText          易读配速（段单位口径）
 * @param avgPaceSecondsPerKm  按距离加权的整体配速（秒/公里），便于跨口径比较
 * @param avgPaceTextPerKm     易读配速（公里口径）
 */
public record OfficialSplits(
        String runId,
        String unit,
        String source,
        double unitMeters,
        double coveredKm,
        List<OfficialSplit> splits,
        OfficialSplit fastest,
        OfficialSplit slowest,
        Double avgPaceSecondsPerUnit,
        String avgPaceText,
        Double avgPaceSecondsPerKm,
        String avgPaceTextPerKm) {

    /** 没有可用分段：出厂值走这个形态，调用方不用判空。 */
    public static OfficialSplits empty(String runId, String unit, String source, double unitMeters) {
        return new OfficialSplits(runId, unit, source, unitMeters, 0d, List.of(), null, null,
                null, null, null, null);
    }

    public boolean usable() {
        return !splits.isEmpty();
    }

    /** 整段（不含末段不足一单位）的数量：只有整段之间才适合直接比配速。 */
    public int fullSplitCount() {
        return (int) splits.stream().filter(split -> !split.partial()).count();
    }
}
