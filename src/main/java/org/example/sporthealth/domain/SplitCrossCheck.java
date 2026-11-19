package org.example.sporthealth.domain;

/**
 * GPS 分段与厂商官方分段的交叉校验结果。
 *
 * <p>两种分段口径不同（GPS 插值 vs 设备自算），差值本身不代表谁对谁错：差值稳定说明轨迹可信，
 * 差值忽大忽小通常意味着 GPS 信号（隧道、楼间、操场绕圈）影响了其中一方。
 *
 * @param comparedSplits              实际参与比较的段数
 * @param meanAbsDeltaSecondsPerKm    每公里配速绝对差的平均（秒/公里）
 * @param maxAbsDeltaSecondsPerKm     最大绝对差（秒/公里）
 * @param maxDeltaAtKm                最大差出现在第几公里
 * @param verdict                     一句话结论
 */
public record SplitCrossCheck(
        int comparedSplits,
        double meanAbsDeltaSecondsPerKm,
        double maxAbsDeltaSecondsPerKm,
        double maxDeltaAtKm,
        String verdict) {
}
