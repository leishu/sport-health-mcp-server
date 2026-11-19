package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 单次跑步的训练类型识别结果。
 *
 * <p>主判据是「距离 / 时长 / 配速相对个人中位数 / 分段配速波动」的启发式规则，
 * 属于辅助判断而不是训练学定论。华为来源的记录还带平均心率，会再换算成心率区间做交叉验证：
 * 心率与配速信号一致时提高置信度，冲突时下调并在 {@code reasons} 里写明原因。
 * 每条结果都带 {@code reasons}，让调用方能解释为什么这样分类。
 *
 * @param runId                        记录 id
 * @param date                         本地日期
 * @param type                         类型标识：EASY / LONG_RUN / TEMPO / INTERVAL / RACE / RECOVERY
 * @param typeText                     中文类型名
 * @param confidence                   置信度：HIGH / MEDIUM / LOW
 * @param reasons                      判定依据
 * @param distanceKm                   距离（公里）
 * @param paceSecondsPerKm             平均配速（秒/公里）
 * @param paceText                     易读配速
 * @param baselinePaceSecondsPerKm     个人中位配速（秒/公里）
 * @param baselinePaceText             个人中位配速文本
 * @param paceStdDevSeconds            分段配速标准差（秒），无轨迹时为 null
 * @param gradeAdjustedPaceSecondsPerKm 坡度修正配速（秒/公里），无海拔/轨迹时为 null
 * @param gradeAdjustedPaceText        坡度修正配速文本
 * @param text                         一句话结论
 * @param heartRate                    心率区间判定，无心率数据（TCX 来源）时为 null
 */
public record RunClassification(
        String runId,
        LocalDate date,
        String type,
        String typeText,
        String confidence,
        List<String> reasons,
        double distanceKm,
        Double paceSecondsPerKm,
        String paceText,
        Double baselinePaceSecondsPerKm,
        String baselinePaceText,
        Double paceStdDevSeconds,
        Double gradeAdjustedPaceSecondsPerKm,
        String gradeAdjustedPaceText,
        String text,
        HeartRate heartRate) {

    /**
     * 一次跑步的心率区间判定。
     *
     * <p>有静息心率时按储备心率（Karvonen）算百分比，否则退化成占最大心率百分比：
     * 两者区间边界不同，{@code percentBasis} 会写明用的哪一套，避免把两种口径混着看。
     *
     * @param avgHeartRate          平均心率（次/分）
     * @param maxHeartRate          本次最高心率（次/分），缺失为 null
     * @param restingHeartRate      静息心率基准（次/分），缺失为 null
     * @param maxHeartRateReference 最大心率基准（次/分），取数据集中观察到的最快值
     * @param percentBasis          百分比口径：HRR（储备心率）或 HRMAX（最大心率）
     * @param percent               平均心率占该口径的百分比
     * @param zone                  区间标识：Z1~Z5
     * @param zoneText              区间中文名
     * @param signal                与配速判定的关系：SUPPORT / NEUTRAL / CONFLICT
     * @param text                  一句话描述
     */
    public record HeartRate(
            Integer avgHeartRate,
            Integer maxHeartRate,
            Integer restingHeartRate,
            Integer maxHeartRateReference,
            String percentBasis,
            double percent,
            String zone,
            String zoneText,
            String signal,
            String text) {
    }
}
