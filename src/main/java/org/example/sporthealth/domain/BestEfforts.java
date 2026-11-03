package org.example.sporthealth.domain;

import java.util.List;

/**
 * 一次跑步内多个目标距离的最佳努力。
 *
 * @param runId              记录 id
 * @param gpsDistanceKm      轨迹累计距离（公里），为 0 表示没有可用 GPS
 * @param efforts            可计算的最佳成绩，按目标距离升序
 * @param unavailableTargets 超出本次距离、无法计算的目标（米）
 */
public record BestEfforts(
        String runId,
        double gpsDistanceKm,
        List<BestEffort> efforts,
        List<Double> unavailableTargets) {
}
