package org.example.sporthealth.domain;

/**
 * 海拔剖面中的一个距离桶。
 *
 * @param fromKm            桶起点（公里）
 * @param toKm              桶终点（公里）
 * @param avgAltitudeMeters 平均海拔（米），桶内无海拔数据时为 null
 * @param minAltitudeMeters 最低海拔（米）
 * @param maxAltitudeMeters 最高海拔（米）
 * @param gainMeters        桶内累计爬升（米）
 * @param lossMeters        桶内累计下降（米）
 * @param avgGradePercent   桶内平均坡度（百分比），正值上坡
 */
public record ElevationBucket(
        double fromKm,
        double toKm,
        Double avgAltitudeMeters,
        Double minAltitudeMeters,
        Double maxAltitudeMeters,
        double gainMeters,
        double lossMeters,
        Double avgGradePercent) {
}
