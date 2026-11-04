package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.TrackPoint;

/**
 * SQLite 行的读写映射。时间统一存 ISO-8601 文本：可读、可比较、可直接用 {@code sqlite3} 查，
 * 且不会像 epoch 数字那样在排查时失去语境。
 */
public class RunRows {

    private RunRows() {
    }

    /** {@code track_point} 一行的原始形态，按 {@code seq} 升序读出。 */
    public record TrackRow(int seq, LocalDateTime timestamp, Double latitude, Double longitude, Double altitude) {

        TrackPoint toTrackPoint() {
            return new TrackPoint(timestamp, latitude, longitude, altitude);
        }
    }

    /** {@code run_activity} 的扁平行，字段与列一一对应，读出来再做类型转换。 */
    public record ActivityRow(
            String id,
            String activityId,
            String sport,
            String runDate,
            String startTime,
            String endTime,
            String startTimeUtc,
            double durationSeconds,
            double distanceMeters,
            Double climbMeters,
            Double descentMeters,
            String routeType,
            String routeTypeText,
            int trackPointCount,
            Double avgSpeedKmh,
            Double avgPaceSeconds,
            Double startLatitude,
            Double startLongitude,
            Double endLatitude,
            Double endLongitude,
            Double minAltitudeMeters,
            Double maxAltitudeMeters,
            String sourceFile,
            String creator) {
    }

    /** 本次同步的统计，用于日志与 {@code reload_data} 的可观测输出。 */
    public record SyncResult(
            int scanned,
            int imported,
            int unchanged,
            List<String> missingSourceFiles,
            List<String> warnings,
            Instant syncedAt) {

        public static SyncResult empty(Instant at) {
            return new SyncResult(0, 0, 0, List.of(), List.of(), at);
        }
    }

    static RunActivity toActivity(ActivityRow row) {
        return new RunActivity(
                row.id(),
                row.activityId(),
                row.sport(),
                date(row.runDate()),
                dateTime(row.startTime()),
                dateTime(row.endTime()),
                instant(row.startTimeUtc()),
                row.durationSeconds(),
                row.distanceMeters(),
                row.climbMeters(),
                row.descentMeters(),
                row.routeType(),
                row.routeTypeText(),
                row.trackPointCount(),
                row.avgSpeedKmh(),
                row.avgPaceSeconds(),
                row.startLatitude(),
                row.startLongitude(),
                row.endLatitude(),
                row.endLongitude(),
                row.minAltitudeMeters(),
                row.maxAltitudeMeters(),
                row.sourceFile(),
                row.creator());
    }

    static String text(LocalDate value) {
        return value == null ? null : value.toString();
    }

    static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    static LocalDateTime dateTime(String value) {
        return value == null ? null : LocalDateTime.parse(value);
    }

    /** {@code Instant} 单独一个重载名，避免与 {@code LocalDateTime} 的文本形式混淆。 */
    /**
     * 读可空 REAL 列。
     *
     * <p>不能用 {@code rs.getObject(name, Double.class)}：sqlite-jdbc 3.x 对 NULL 会抛
     * {@code Bad value for type Double}，而缺海拔、缺坐标的记录恰恰是 NULL。
     */
    static Double real(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    static String instantText(Instant value) {
        return value == null ? null : value.toString();
    }

    static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
