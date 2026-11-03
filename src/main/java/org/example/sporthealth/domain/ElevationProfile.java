package org.example.sporthealth.domain;

import java.util.List;

/**
 * 一次跑步的海拔剖面，按固定距离分桶。
 *
 * <p>爬升/下降用“参考海拔”法计算：只有相对上次记录的参考点变化超过
 * {@code smoothingMeters} 才计入，避免 GPS 高程抖动把累计爬升算大。
 *
 * @param runId             记录 id
 * @param date              本地日期
 * @param bucketMeters      每个桶覆盖的距离（米）
 * @param smoothingMeters   爬升计算的平滑阈值（米），0 表示不做平滑
 * @param gpsDistanceKm     轨迹累计距离（公里）
 * @param totalGainMeters   全程累计爬升（米），无海拔数据时为 null
 * @param totalLossMeters   全程累计下降（米），无海拔数据时为 null
 * @param minAltitudeMeters 全程最低海拔（米），无海拔数据时为 null
 * @param maxAltitudeMeters 全程最高海拔（米），无海拔数据时为 null
 * @param buckets           按距离升序的桶列表
 */
public record ElevationProfile(
        String runId,
        java.time.LocalDate date,
        double bucketMeters,
        double smoothingMeters,
        double gpsDistanceKm,
        Double totalGainMeters,
        Double totalLossMeters,
        Double minAltitudeMeters,
        Double maxAltitudeMeters,
        List<ElevationBucket> buckets) {
}
