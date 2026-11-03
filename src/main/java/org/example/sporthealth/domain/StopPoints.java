package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 一次跑步中检测到的全部停顿。
 *
 * @param runId            记录 id
 * @param date             本地日期
 * @param stopCount        停顿次数
 * @param totalStopSeconds 停顿总时长（秒）
 * @param totalStopText    停顿总时长的易读形式
 * @param stops            停顿明细，按时间升序
 */
public record StopPoints(
        String runId,
        LocalDate date,
        int stopCount,
        double totalStopSeconds,
        String totalStopText,
        List<StopPoint> stops) {
}
