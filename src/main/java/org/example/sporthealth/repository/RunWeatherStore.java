package org.example.sporthealth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.domain.WeatherSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code run_weather} 的读写：挑出待补的记录、UPSERT 抓取结果、汇总覆盖率、按记录读回天气。
 *
 * <p>待补的判定是<b>只补缺的</b>：
 * <ul>
 *   <li>没有对应的 {@code run_weather} 行 → 需要查；</li>
 *   <li>{@code status = 'FAILED'} 且上次抓取早于冷却时间 → 需要重试；</li>
 *   <li>{@code OK} / {@code UNAVAILABLE} → 不再查询。后者是「接口没有这个时段的数据」，
 *       历史存档一般不会补，反复问只是白花配额。</li>
 * </ul>
 *
 * <p><b>坐标无效的记录也会被选中</b>：{@code SELECT_PENDING} 不判坐标，因为「有效坐标」的规则
 * （缺字段、0,0 漂移、越界）与 {@code WeatherBackfill.startOf/endOf} 完全一致才有意义，
 * 在 SQL 里再写一遍只会造出两套会走偏的判断。它们被选中后直接标 {@code UNAVAILABLE}，不发请求。
 *
 * <p>写入用 {@code ON CONFLICT DO UPDATE}：同一 run 重复回填只更新不新增，
 * 保证 {@code reload_data} 可以反复调用。
 *
 * <p>读统计一律 {@code JOIN run_activity}：{@code run_weather} 没有外键，
 * 历史遗留的孤儿行（对应记录已被重导删除）不应该进入覆盖率，否则能算出超过 100% 的百分比。
 */
@Component
public class RunWeatherStore {

    private static final String SELECT_PENDING = """
            SELECT r.id, r.start_time_utc, r.duration_seconds,
                   r.start_latitude, r.start_longitude,
                   COALESCE(r.end_latitude, r.start_latitude)     AS end_latitude,
                   COALESCE(r.end_longitude, r.start_longitude)   AS end_longitude
            FROM run_activity r
            LEFT JOIN run_weather w ON w.run_id = r.id
            WHERE w.run_id IS NULL
               OR (w.status = 'FAILED' AND w.fetched_epoch_millis < :retryBeforeMillis)
            ORDER BY r.start_time_utc DESC
            LIMIT :limit
            """;

    private static final String UPSERT = """
            INSERT INTO run_weather (
                run_id, start_obs_time_utc, end_obs_time_utc,
                start_latitude, start_longitude, end_latitude, end_longitude,
                start_temperature_c, start_apparent_c, start_humidity_percent,
                start_precipitation_mm, start_wind_speed_kmh, start_wind_direction, start_weather_code,
                end_temperature_c, end_apparent_c, end_humidity_percent,
                end_precipitation_mm, end_wind_speed_kmh, end_wind_direction, end_weather_code,
                provider, fetched_at, fetched_epoch_millis, status, error_message
            ) VALUES (
                :runId, :startObs, :endObs,
                :startLat, :startLon, :endLat, :endLon,
                :startTemp, :startApparent, :startHumidity,
                :startPrecip, :startWindSpeed, :startWindDir, :startCode,
                :endTemp, :endApparent, :endHumidity,
                :endPrecip, :endWindSpeed, :endWindDir, :endCode,
                :provider, :fetchedAt, :fetchedAtMillis, :status, :errorMessage
            )
            ON CONFLICT (run_id) DO UPDATE SET
                start_obs_time_utc = excluded.start_obs_time_utc,
                end_obs_time_utc = excluded.end_obs_time_utc,
                start_latitude = excluded.start_latitude,
                start_longitude = excluded.start_longitude,
                end_latitude = excluded.end_latitude,
                end_longitude = excluded.end_longitude,
                start_temperature_c = excluded.start_temperature_c,
                start_apparent_c = excluded.start_apparent_c,
                start_humidity_percent = excluded.start_humidity_percent,
                start_precipitation_mm = excluded.start_precipitation_mm,
                start_wind_speed_kmh = excluded.start_wind_speed_kmh,
                start_wind_direction = excluded.start_wind_direction,
                start_weather_code = excluded.start_weather_code,
                end_temperature_c = excluded.end_temperature_c,
                end_apparent_c = excluded.end_apparent_c,
                end_humidity_percent = excluded.end_humidity_percent,
                end_precipitation_mm = excluded.end_precipitation_mm,
                end_wind_speed_kmh = excluded.end_wind_speed_kmh,
                end_wind_direction = excluded.end_wind_direction,
                end_weather_code = excluded.end_weather_code,
                provider = excluded.provider,
                fetched_at = excluded.fetched_at,
                fetched_epoch_millis = excluded.fetched_epoch_millis,
                status = excluded.status,
                error_message = excluded.error_message
            """;

    private final JdbcClient jdbc;

    public RunWeatherStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 需要补天气的跑步记录，新的在前。
     *
     * @param retryBefore {@code FAILED} 记录早于这个时刻才重试，避免每次同步都打一遍失败的
     * @param limit       单次上限，防止首次全量导入一次打光配额
     */
    public List<PendingRun> pending(Instant retryBefore, int limit) {
        return jdbc.sql(SELECT_PENDING)
                // 用整数毫秒比较，不用 ISO 文本：文本比大小对位数敏感，
                // 一边带纳秒一边只到秒时 "2026-10-25T22:56:16.433Z" 与 "2026-10-25T22:56:16Z"
                // 的字典序结果与真实时间先后不一致，冷却判断会悄悄失效
                .param("retryBeforeMillis", retryBefore.toEpochMilli())
                .param("limit", limit)
                .query((rs, rowNum) -> new PendingRun(
                        rs.getString("id"),
                        rs.getString("start_time_utc"),
                        RunRows.real(rs, "duration_seconds"),
                        RunRows.real(rs, "start_latitude"),
                        RunRows.real(rs, "start_longitude"),
                        RunRows.real(rs, "end_latitude"),
                        RunRows.real(rs, "end_longitude")))
                .list();
    }

    /** 写入或覆盖一条天气记录。 */
    public void save(RunWeather weather) {
        var spec = jdbc.sql(UPSERT)
                .param("runId", weather.runId())
                .param("provider", weather.provider())
                .param("fetchedAt", RunRows.instantText(weather.fetchedAt()))
                .param("fetchedAtMillis", weather.fetchedAt().toEpochMilli())
                .param("status", weather.status().name())
                .param("errorMessage", weather.errorMessage())
                .param("startLat", weather.startLatitude())
                .param("startLon", weather.startLongitude())
                .param("endLat", weather.endLatitude())
                .param("endLon", weather.endLongitude());

        spec = bindSnapshot(spec, "start", weather.start());
        spec = bindSnapshot(spec, "end", weather.end());
        spec.update();
    }

    private static JdbcClient.StatementSpec bindSnapshot(JdbcClient.StatementSpec spec, String prefix,
                                                         org.example.sporthealth.domain.WeatherSnapshot snapshot) {
        if (snapshot == null) {
            return spec
                    .param(prefix + "Obs", null)
                    .param(prefix + "Temp", null)
                    .param(prefix + "Apparent", null)
                    .param(prefix + "Humidity", null)
                    .param(prefix + "Precip", null)
                    .param(prefix + "WindSpeed", null)
                    .param(prefix + "WindDir", null)
                    .param(prefix + "Code", null);
        }
        return spec
                .param(prefix + "Obs", RunRows.instantText(snapshot.observedAtUtc()))
                .param(prefix + "Temp", snapshot.temperatureC())
                .param(prefix + "Apparent", snapshot.apparentTemperatureC())
                .param(prefix + "Humidity", snapshot.humidityPercent())
                .param(prefix + "Precip", snapshot.precipitationMm())
                .param(prefix + "WindSpeed", snapshot.windSpeedKmh())
                .param(prefix + "WindDir", snapshot.windDirectionDegrees() == null
                        ? null : Math.round(snapshot.windDirectionDegrees()))
                .param(prefix + "Code", snapshot.weatherCode());
    }

    /**
     * 统计各状态条数，供 {@code describe_data_source} 与日志使用。
     *
     * <p>只统计还存在于 {@code run_activity} 的天气行：孤儿行来自历史遗留（记录被重导删除），
     * 算进来会让 {@code ok} 大于 {@code totalRuns}，覆盖率超过 100%。
     */
    public Stats stats() {
        List<StatusCount> rows = jdbc.sql("""
                        SELECT w.status, COUNT(*) AS cnt
                        FROM run_weather w
                        JOIN run_activity r ON r.id = w.run_id
                        GROUP BY w.status
                        """)
                .query((rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getInt("cnt")))
                .list();
        int ok = 0;
        int unavailable = 0;
        int failed = 0;
        for (StatusCount row : rows) {
            switch (row.status()) {
                case "OK" -> ok = row.count();
                case "UNAVAILABLE" -> unavailable = row.count();
                case "FAILED" -> failed = row.count();
                default -> {
                    // 未知状态不计入，宁可低估也不要把脏数据算成成功
                }
            }
        }
        int totalRuns = jdbc.sql("SELECT COUNT(*) FROM run_activity").query(Integer.class).single();
        String lastFetchedAt = jdbc.sql("""
                        SELECT MAX(w.fetched_at)
                        FROM run_weather w
                        JOIN run_activity r ON r.id = w.run_id
                        """)
                .query(String.class).optional()
                .orElse(null);
        return new Stats(totalRuns, ok, unavailable, failed, Math.max(0, totalRuns - ok - unavailable - failed),
                lastFetchedAt);
    }

    /**
     * 按记录 id 读回天气，供 {@code get_run_detail} 输出该次跑步的天气。
     *
     * <p><b>不按 status 过滤</b>：{@code UNAVAILABLE} / {@code FAILED} 也要能读出来，
     * 调用方需要向用户说明「为什么没有天气」（缺坐标、超出存档范围、抓取失败），
     * 把失败行当成不存在只会让用户以为功能没生效。没有这一行才返回空，表示还没回填过。
     */
    public Optional<RunWeather> find(String runId) {
        return jdbc.sql("""
                        SELECT run_id,
                               start_obs_time_utc, start_temperature_c, start_apparent_c,
                               start_humidity_percent, start_precipitation_mm,
                               start_wind_speed_kmh, start_wind_direction, start_weather_code,
                               end_obs_time_utc, end_temperature_c, end_apparent_c,
                               end_humidity_percent, end_precipitation_mm,
                               end_wind_speed_kmh, end_wind_direction, end_weather_code,
                               start_latitude, start_longitude, end_latitude, end_longitude,
                               status, error_message, fetched_at, provider
                        FROM run_weather
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new RunWeather(
                        rs.getString("run_id"),
                        snapshot(rs, "start"),
                        snapshot(rs, "end"),
                        RunRows.real(rs, "start_latitude"),
                        RunRows.real(rs, "start_longitude"),
                        RunRows.real(rs, "end_latitude"),
                        RunRows.real(rs, "end_longitude"),
                        RunWeather.Status.valueOf(rs.getString("status")),
                        rs.getString("error_message"),
                        instant(rs.getString("fetched_at")),
                        rs.getString("provider")))
                .optional();
    }

    /**
     * 清理 {@code run_activity} 中已不存在的天气行。
     *
     * <p>表上没有外键（SQLite 默认不开启级联），删除记录时必须显式清理；
     * 这里作为每次同步结束后的兜底，覆盖历史遗留与异常中断留下的行。
     *
     * @return 删除的行数
     */
    public int deleteOrphans() {
        return jdbc.sql("""
                        DELETE FROM run_weather
                        WHERE NOT EXISTS (SELECT 1 FROM run_activity r WHERE r.id = run_weather.run_id)
                        """)
                .update();
    }

    private static WeatherSnapshot snapshot(ResultSet rs, String prefix) throws SQLException {
        String observedAt = rs.getString(prefix + "_obs_time_utc");
        Integer code = integer(rs, prefix + "_weather_code");
        WeatherSnapshot snapshot = new WeatherSnapshot(
                observedAt == null ? null : Instant.parse(observedAt),
                RunRows.real(rs, prefix + "_temperature_c"),
                RunRows.real(rs, prefix + "_apparent_c"),
                RunRows.real(rs, prefix + "_humidity_percent"),
                RunRows.real(rs, prefix + "_precipitation_mm"),
                RunRows.real(rs, prefix + "_wind_speed_kmh"),
                RunRows.real(rs, prefix + "_wind_direction"),
                code);
        // 一整行都是 null 时没有快照可言，与 RunWeather.partialMessage 的口径保持一致
        return snapshot.isEmpty() ? null : snapshot;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }

    private record StatusCount(String status, int count) {
    }

    /**
     * 待补记录的最小信息：从 {@code run_activity} 读出来的原始字段。
     *
     * @param endLatitude  {@code COALESCE(end_latitude, start_latitude)}，终点缺失时退回起点
     * @param endLongitude 同上
     */
    public record PendingRun(
            String runId,
            String startTimeUtc,
            double durationSeconds,
            Double startLatitude,
            Double startLongitude,
            Double endLatitude,
            Double endLongitude) {
    }

    /**
     * 覆盖率统计。
     *
     * @param totalRuns   库中跑步记录总数
     * @param ok          天气已取到
     * @param unavailable 接口未覆盖该时段（终态）
     * @param failed      抓取失败，等待重试
     * @param missing     还没查过
     */
    public record Stats(
            int totalRuns,
            int ok,
            int unavailable,
            int failed,
            int missing,
            String lastFetchedAt) {
    }
}
