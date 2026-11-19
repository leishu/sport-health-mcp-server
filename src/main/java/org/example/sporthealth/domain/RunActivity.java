package org.example.sporthealth.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次运动记录的摘要，不含推测数据。
 *
 * <p>字段分两块：前 24 个来自轨迹与 laps，TCX 与华为 JSON 都有；
 * 末尾 10 个是<b>华为导出独有</b>的心率/步频/步数/热量等，TCX 来源的记录一律为 {@code null}
 * （{@link #RunActivity(String, String, String, java.time.LocalDate, LocalDateTime, LocalDateTime,
 * Instant, double, double, Double, Double, String, String, int, Double, Double, Double, Double,
 * Double, Double, Double, Double, String, String)} 就是给 TCX 用的）。
 *
 * <p>时间字段分三组：
 * <ul>
 *   <li>{@code date} / {@code startTime} / {@code endTime}：已按配置时区换算的本地时间，面向用户；</li>
 *   <li>{@code startTimeUtc}：原始 UTC 时间，便于和其它数据源对齐；</li>
 *   <li>{@code date} 只取开始日期，跨零点的跑步仍归属开始那天。</li>
 * </ul>
 *
 * @param id                 项目内唯一标识：TCX 取文件名（不含扩展名），华为取 {@code hw-yyyyMMdd-HHmm-dataId}
 * @param activityId         原始活动 id：TCX 是 {@code Activity/Id}，华为是开始时刻的 UTC ISO 串
 * @param sport              运动名称，例如“户外跑步”
 * @param date               本地日期
 * @param startTime          本地开始时间
 * @param endTime            本地结束时间（开始时间 + 总时长）
 * @param startTimeUtc       原始 UTC 开始时间
 * @param durationSeconds    总时长（秒）
 * @param distanceMeters     总距离（米）
 * @param climbMeters        累计爬升（米），缺失为 null
 * @param descentMeters      累计下降（米），缺失为 null
 * @param routeType          {@code Lap/Extensions/RouteType} 原始值，缺失为 null
 * @param routeTypeText      RouteType 的可读语义（OUTDOOR/INDOOR/UNKNOWN），缺失为 null
 * @param trackPointCount    轨迹点数量
 * @param avgSpeedKmh        平均速度（公里/小时）
 * @param avgPaceSecondsPerKm 平均配速（秒/公里）
 * @param startLatitude      起点纬度，无有效轨迹时为 null
 * @param startLongitude     起点经度，无有效轨迹时为 null
 * @param endLatitude        终点纬度，无有效轨迹时为 null
 * @param endLongitude       终点经度，无有效轨迹时为 null
 * @param minAltitudeMeters  最低海拔（米），无海拔数据时为 null
 * @param maxAltitudeMeters  最高海拔（米），无海拔数据时为 null
 * @param sourceFile         来源文件名
 * @param creator            TCX 根节点 {@code creator}，华为固定 {@code HUAWEI_HEALTH}
 * @param avgHeartRate       平均心率（次/分），华为独有
 * @param maxHeartRate       最高心率（次/分），华为独有
 * @param minHeartRate       最低心率（次/分），华为独有且常常缺失（只有部分机型给）
 * @param avgCadence         平均步频（步/分），华为独有
 * @param totalSteps         总步数，华为独有
 * @param totalCaloriesKcal  消耗热量（千卡），华为独有
 * @param recordId           华为数据中心侧记录 id，华为独有
 * @param sportTypeCode      华为 {@code summaryData.sportType}（258 跑步、257 步行），TCX 为 null
 * @param subType            华为 {@code subType}，华为独有
 * @param summaryData        华为 {@code summaryData} 原文（JSON，约 2.5 KB），未建模字段可从中取
 */
public record RunActivity(
        String id,
        String activityId,
        String sport,
        LocalDate date,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Instant startTimeUtc,
        double durationSeconds,
        double distanceMeters,
        Double climbMeters,
        Double descentMeters,
        String routeType,
        String routeTypeText,
        int trackPointCount,
        Double avgSpeedKmh,
        Double avgPaceSecondsPerKm,
        Double startLatitude,
        Double startLongitude,
        Double endLatitude,
        Double endLongitude,
        Double minAltitudeMeters,
        Double maxAltitudeMeters,
        String sourceFile,
        String creator,
        Integer avgHeartRate,
        Integer maxHeartRate,
        Integer minHeartRate,
        Integer avgCadence,
        Integer totalSteps,
        Double totalCaloriesKcal,
        String recordId,
        Integer sportTypeCode,
        Integer subType,
        String summaryData) {

    /** TCX 来源的记录：没有心率/步频/步数这些华为字段，统一留空。 */
    public RunActivity(String id,
                       String activityId,
                       String sport,
                       LocalDate date,
                       LocalDateTime startTime,
                       LocalDateTime endTime,
                       Instant startTimeUtc,
                       double durationSeconds,
                       double distanceMeters,
                       Double climbMeters,
                       Double descentMeters,
                       String routeType,
                       String routeTypeText,
                       int trackPointCount,
                       Double avgSpeedKmh,
                       Double avgPaceSecondsPerKm,
                       Double startLatitude,
                       Double startLongitude,
                       Double endLatitude,
                       Double endLongitude,
                       Double minAltitudeMeters,
                       Double maxAltitudeMeters,
                       String sourceFile,
                       String creator) {
        this(id, activityId, sport, date, startTime, endTime, startTimeUtc, durationSeconds,
                distanceMeters, climbMeters, descentMeters, routeType, routeTypeText, trackPointCount,
                avgSpeedKmh, avgPaceSecondsPerKm, startLatitude, startLongitude, endLatitude,
                endLongitude, minAltitudeMeters, maxAltitudeMeters, sourceFile, creator,
                null, null, null, null, null, null, null, null, null, null);
    }
}
