package org.example.sporthealth.domain;

import java.time.LocalDateTime;

/**
 * 轨迹中间的一次停顿（等红灯、补水、休息等）。
 *
 * <p>判定条件：相邻两个有效坐标点之间间隔不小于阈值秒数，且位移不超过阈值米数。
 * 连续满足条件的相邻点会合并为同一次停顿。
 *
 * @param startTime       停顿开始时间（本地时间）
 * @param endTime         停顿结束时间（本地时间）
 * @param durationSeconds 停顿时长（秒）
 * @param durationText    易读时长
 * @param latitude        停顿位置纬度
 * @param longitude       停顿位置经度
 * @param altitudeMeters  停顿位置海拔（米），缺失为 null
 */
public record StopPoint(
        LocalDateTime startTime,
        LocalDateTime endTime,
        double durationSeconds,
        String durationText,
        Double latitude,
        Double longitude,
        Double altitudeMeters) {
}
