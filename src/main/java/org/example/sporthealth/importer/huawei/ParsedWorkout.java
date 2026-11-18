package org.example.sporthealth.importer.huawei;

import java.time.Instant;
import java.util.List;

import org.example.sporthealth.domain.TrackPoint;

/**
 * 一次华为运动记录解析后的中间形态，尚未落库。
 *
 * <p>字段全部来自导出的 {@code summaryData} 与 {@code detailData}，没有推测值：
 * 缺失的量保持 {@code null}，由写入层落成 SQL NULL。
 *
 * @param id                项目内唯一标识，形如 {@code hw-20251115-1726-134}
 * @param recordId          华为 {@code recordId}，跨导出批次稳定，用于去重
 * @param startTime         开始时刻（UTC）
 * @param endTime           结束时刻（UTC）
 * @param typeCode          华为 {@code type}，运动记录恒为 30001
 * @param subType           华为 {@code subType}，4=户外跑步、5=户外步行、112/283=其它
 * @param sportTypeCode     华为 {@code summaryData.sportType}，258=跑步、257=步行
 * @param sport             可读运动名称，写入 {@code run_activity.sport}
 * @param sportFamily       {@code RUN} / {@code WALK} / {@code OTHER}，决定是否参与跑步分析
 * @param distanceMeters    总距离（米）
 * @param durationSeconds   总时长（秒）
 * @param caloriesKcal      消耗热量（千卡），由导出的 cal 除以 1000 换算
 * @param totalSteps        总步数
 * @param avgHeartRate      平均心率（bpm）
 * @param maxHeartRate      最大心率（bpm）
 * @param minHeartRate      最小心率（bpm）
 * @param avgCadence        平均步频（步/分）
 * @param climbMeters       累计爬升（米），由气压高度序列累加，估算值
 * @param descentMeters     累计下降（米），优先取导出汇总值（原单位分米，已换算）
 * @param trackPoints       轨迹点（已转 WGS-84），可能为空
 */
public record ParsedWorkout(
        String id,
        String recordId,
        Instant startTime,
        Instant endTime,
        int typeCode,
        int subType,
        int sportTypeCode,
        String sport,
        String sportFamily,
        double distanceMeters,
        double durationSeconds,
        double caloriesKcal,
        int totalSteps,
        Integer avgHeartRate,
        Integer maxHeartRate,
        Integer minHeartRate,
        Integer avgCadence,
        Double climbMeters,
        Double descentMeters,
        List<TrackPoint> trackPoints) {

    /** 是否有可用轨迹（掉落 GPS 的室内运动为 false）。 */
    public boolean hasTrack() {
        return !trackPoints.isEmpty();
    }
}
