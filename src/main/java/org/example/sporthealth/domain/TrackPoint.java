package org.example.sporthealth.domain;

import java.time.LocalDateTime;

/**
 * 单个轨迹点。
 *
 * <p>经纬度用包装类型而不是 {@code double}：华为导出里存在缺少 {@code Position}
 * 的轨迹点，缺失时用 {@code null} 表示。之前的实现用 {@code Double.NaN} 当哨兵，
 * 经 Jackson 序列化会变成字符串 {@code "NaN"}，客户端拿到的是非法数字类型。
 *
 * @param timestamp     本地时间（已由 UTC 换算到配置时区）
 * @param latitude      纬度，缺失为 null
 * @param longitude     经度，缺失为 null
 * @param altitudeMeters 海拔（米），缺失为 null
 */
public record TrackPoint(
        LocalDateTime timestamp,
        Double latitude,
        Double longitude,
        Double altitudeMeters) {

    /** 是否包含可用的经纬度（缺坐标或 (0,0) 视为无效）。 */
    public boolean hasPosition() {
        return latitude != null && longitude != null
                && !(latitude == 0d && longitude == 0d);
    }
}
