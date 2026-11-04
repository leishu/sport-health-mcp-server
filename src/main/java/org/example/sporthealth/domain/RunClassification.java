package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 单次跑步的训练类型识别结果。
 *
 * <p>没有心率数据，分类基于「距离 / 时长 / 配速相对个人中位数 / 分段配速波动」的启发式规则，
 * 属于辅助判断而不是训练学定论。每条结果都带 {@code reasons}，让调用方能解释为什么这样分类。
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
        String text) {
}
