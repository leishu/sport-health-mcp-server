package org.example.sporthealth.domain;

import java.time.LocalDateTime;

/**
 * 单个轨迹点。
 *
 * <p>经纬度用包装类型而不是 {@code double}：华为导出里存在缺少 {@code Position}
 * 的轨迹点，缺失时用 {@code null} 表示。之前的实现用 {@code Double.NaN} 当哨兵，
 * 经 Jackson 序列化会变成字符串 {@code "NaN"}，客户端拿到的是非法数字类型。
 *
 * <p>心率、步频、累计距离来自华为 JSON 导出（TCX 没有这些字段，恒为 {@code null}）：
 * 它们按时间就近匹配到轨迹点上，用来回答「这段上坡心率多少」「后半程步频掉没掉」这类问题。
 *
 * @param timestamp      本地时间（已由 UTC 换算到配置时区）
 * @param latitude       纬度，缺失为 null
 * @param longitude      经度，缺失为 null
 * @param altitudeMeters 海拔（米），缺失为 null
 * @param heartRate      该时刻心率（bpm），缺失为 null
 * @param cadence        该时刻步频（步/分），缺失为 null
 * @param distanceMeters 运动开始到该点的累计距离（米），缺失为 null
 */
public record TrackPoint(
        LocalDateTime timestamp,
        Double latitude,
        Double longitude,
        Double altitudeMeters,
        Integer heartRate,
        Integer cadence,
        Double distanceMeters) {

    /** TCX 轨迹点：没有心率、步频与累计距离。 */
    public TrackPoint(LocalDateTime timestamp, Double latitude, Double longitude, Double altitudeMeters) {
        this(timestamp, latitude, longitude, altitudeMeters, null, null, null);
    }

    /** 是否包含可用的经纬度（缺坐标或 (0,0) 视为无效）。 */
    public boolean hasPosition() {
        return latitude != null && longitude != null
                && !(latitude == 0d && longitude == 0d);
    }
}
