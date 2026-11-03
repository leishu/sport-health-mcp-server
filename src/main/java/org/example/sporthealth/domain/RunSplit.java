package org.example.sporthealth.domain;

/**
 * 一次跑步中的一段（默认按 1 公里切分）。
 *
 * @param index                 段序号，从 1 开始
 * @param fromKm                起始距离（公里）
 * @param toKm                  结束距离（公里）
 * @param distanceKm            本段距离（公里）
 * @param durationSeconds       本段用时（秒），含停顿
 * @param paceSecondsPerKm      本段配速（秒/公里），时长为 0 时为 null
 * @param paceText              配速文本，例如 5'45"/km
 * @param elevationGainMeters   本段累计爬升（米），无海拔数据时为 null
 * @param partial               是否为最后一段不满整段的余量
 */
public record RunSplit(
        int index,
        double fromKm,
        double toKm,
        double distanceKm,
        double durationSeconds,
        Double paceSecondsPerKm,
        String paceText,
        Double elevationGainMeters,
        boolean partial) {
}
