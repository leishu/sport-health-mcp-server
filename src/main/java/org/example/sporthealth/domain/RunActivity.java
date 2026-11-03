package org.example.sporthealth.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次运动记录的摘要，字段全部来自华为导出的 TCX 文件，不含推测数据。
 *
 * <p>时间字段分三组：
 * <ul>
 *   <li>{@code date} / {@code startTime} / {@code endTime}：已按配置时区换算的本地时间，面向用户；</li>
 *   <li>{@code startTimeUtc}：TCX 中的原始 UTC 时间，便于和其它数据源对齐；</li>
 *   <li>{@code date} 只取开始日期，跨零点的跑步仍归属开始那天。</li>
 * </ul>
 *
 * @param id                 项目内唯一标识，取文件名（不含扩展名），便于口头引用
 * @param activityId         TCX 中 {@code Activity/Id} 的原始值
 * @param sport              TCX 中 {@code Activity/@Sport}，例如“户外跑步”
 * @param date               本地日期
 * @param startTime          本地开始时间
 * @param endTime            本地结束时间（开始时间 + 总时长）
 * @param startTimeUtc       原始 UTC 开始时间
 * @param durationSeconds    总时长（秒）
 * @param distanceMeters     总距离（米）
 * @param climbMeters        累计爬升（米），TCX 未提供时为 null
 * @param descentMeters      累计下降（米），TCX 未提供时为 null
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
 * @param sourceFile         来源 TCX 文件名
 * @param creator            TCX 根节点 {@code creator} 属性，通常是导出应用名
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
        String creator) {
}
