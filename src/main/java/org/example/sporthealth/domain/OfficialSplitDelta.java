package org.example.sporthealth.domain;

/**
 * 一段官方配速与同一段 GPS 配速的差值。
 *
 * <p>差值一律用秒/公里，正数表示<b>官方比 GPS 慢</b>（GPS 认为你跑得更快），负数表示官方更快。
 * 这个方向容易反着读，返回体里配了 {@code deltaText}。
 *
 * <p>{@code cumulativeDeltaSeconds} 是从第 1 段累加到本段的有符号差值秒数：
 * 它等价于「到本段终点为止，两边认定的累计用时差多少」，比逐段差值更抗噪声。
 *
 * @param index                   段序号，与官方分段一致
 * @param fromKm                  起始距离（公里）
 * @param toKm                    结束距离（公里）
 * @param officialPaceSecondsPerKm 官方本段配速（秒/公里）
 * @param officialPaceText        官方配速文本
 * @param gpsPaceSecondsPerKm     GPS 本段配速（秒/公里）
 * @param gpsPaceText             GPS 配速文本
 * @param deltaSecondsPerKm       毫秒级精度的差值（官方 − GPS，秒/公里）
 * @param deltaText               一句话差值，例如 {@code 慢 8 秒/公里}
 * @param cumulativeDeltaSeconds  到本段终点为止的累计差值（官方 − GPS，秒）
 */
public record OfficialSplitDelta(
        int index,
        double fromKm,
        double toKm,
        double officialPaceSecondsPerKm,
        String officialPaceText,
        double gpsPaceSecondsPerKm,
        String gpsPaceText,
        double deltaSecondsPerKm,
        String deltaText,
        double cumulativeDeltaSeconds) {
}
