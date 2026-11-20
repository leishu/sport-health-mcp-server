package org.example.sporthealth.domain;

import java.util.List;

/**
 * 官方分段与 GPS 分段的逐段对比结果（{@code get_official_splits} 的核心）。
 *
 * <p>两种分段口径不同（设备累计距离切分 vs GPS 插值切分），差值本身不正确也不错误：
 * 差得稳定说明两边互相印证，差得忽大忽小通常是其中一方被信号影响（隧道、楼间、操场绕圈、折返）。
 *
 * @param unit                      段单位：{@code km} / {@code mile}
 * @param comparedSplits            实际参与比较的段数（末段长度不一致的段会被跳过）
 * @param meanAbsDeltaSecondsPerKm  每段绝对差的平均（秒/公里）
 * @param maxAbsDeltaSecondsPerKm   最大绝对差（秒/公里）
 * @param maxDeltaAtKm              最大绝对差出现在第几公里（该段终点）
 * @param signedMeanDeltaSecondsPerKm 有符号差的平均：正数表示官方整体比 GPS 慢
 * @param within5SplitCount         绝对差 ≤5 秒/公里的段数
 * @param within15SplitCount        绝对差 ≤15 秒/公里的段数
 * @param verdict                   一句话结论
 * @param splits                    逐段差值，按段序号升序
 */
public record OfficialSplitComparison(
        String unit,
        int comparedSplits,
        double meanAbsDeltaSecondsPerKm,
        double maxAbsDeltaSecondsPerKm,
        double maxDeltaAtKm,
        double signedMeanDeltaSecondsPerKm,
        int within5SplitCount,
        int within15SplitCount,
        String verdict,
        List<OfficialSplitDelta> splits) {
}
