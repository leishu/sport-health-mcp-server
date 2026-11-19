package org.example.sporthealth.domain;

/**
 * 一次跑步中按距离切出的一段（默认 1 公里）的跑姿与步幅，也用于前后半程对比。
 *
 * <p>配速、步频、步幅来自轨迹（{@link org.example.sporthealth.support.TrackPath}），
 * 触地时间、垂直振幅、左右平衡来自跑姿流（{@code track_stream} 的 {@code rp}）按距离落段后的均值。
 * 一段里没有跑姿采样时，这三项为 {@code null}，不用上一段的数值顶替。
 *
 * @param index                  段序号，从 1 开始
 * @param fromKm                 起始距离（公里）
 * @param toKm                   结束距离（公里）
 * @param distanceKm             本段距离（公里）
 * @param durationSeconds        本段用时（秒）
 * @param paceSecondsPerKm       本段配速（秒/公里）
 * @param paceText               本段配速文本
 * @param cadenceSpm             本段平均步频（步/分），按时间加权
 * @param strideLengthMeters     本段平均步幅（米）= 本段速度 × 60 ÷ 本段步频
 * @param groundContactTimeMs    本段平均触地时间（毫秒）
 * @param verticalOscillationCm  本段平均垂直振幅（厘米）
 * @param groundBalancePercent   本段触地平衡（%）
 * @param sampleCount            落在本段内的跑姿采样点数
 * @param partial                是否为最后一段不满整段的余量
 */
public record DynamicsSegment(
        int index,
        double fromKm,
        double toKm,
        double distanceKm,
        double durationSeconds,
        Double paceSecondsPerKm,
        String paceText,
        Integer cadenceSpm,
        Double strideLengthMeters,
        Double groundContactTimeMs,
        Double verticalOscillationCm,
        Double groundBalancePercent,
        int sampleCount,
        boolean partial) {
}
