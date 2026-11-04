package org.example.sporthealth.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.repository.RunRows.ActivityRow;
import org.example.sporthealth.repository.RunRows.SyncResult;
import org.example.sporthealth.repository.RunRows.TrackRow;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 基于 SQLite 的仓储实现：TCX 是导入源，库是事实源。
 *
 * <p>数据流：
 * <ul>
 *   <li>{@link #dataset()}：一条 {@code SELECT} 读出全部摘要，装成不可变 {@link RunDataset} 快照。
 *       上层的区间过滤、按天聚合、趋势统计仍在 Java 里跑，SQL 层只负责取数；</li>
 *   <li>{@link #route(String)}：直接按主键查 {@code track_point}，不再重新解析 XML。
 *       按 id 缓存，命中后免去一次 SQL；</li>
 *   <li>{@link #reload()}：交给 {@link TcxIncrementalImporter} 做增量同步，然后重建快照。</li>
 * </ul>
 *
 * <p>并发：{@code snapshot} 与 {@code routeCache} 都是 volatile 替换，不做原地修改。
 * 首次访问与 reload 用方法锁串行，避免同一进程内并发导入。
 */
@Repository
@Primary
@Slf4j
public class JdbcHealthDataRepository implements HealthDataRepository {

    private static final String SELECT_ACTIVITIES = """
            SELECT id, activity_id, sport, run_date, start_time, end_time, start_time_utc,
                   duration_seconds, distance_meters, climb_meters, descent_meters,
                   route_type, route_type_text, track_point_count, avg_speed_kmh, avg_pace_seconds,
                   start_latitude, start_longitude, end_latitude, end_longitude,
                   min_altitude_meters, max_altitude_meters, source_file, creator
            FROM run_activity
            ORDER BY start_time DESC
            """;

    private static final String SELECT_TRACK_POINTS = """
            SELECT seq, timestamp, latitude, longitude, altitude
            FROM track_point
            WHERE run_id = ?
            ORDER BY seq
            """;

    private static final RowMapper<ActivityRow> ACTIVITY_MAPPER = (rs, rowNum) -> new ActivityRow(
            rs.getString("id"),
            rs.getString("activity_id"),
            rs.getString("sport"),
            rs.getString("run_date"),
            rs.getString("start_time"),
            rs.getString("end_time"),
            rs.getString("start_time_utc"),
            rs.getDouble("duration_seconds"),
            rs.getDouble("distance_meters"),
            RunRows.real(rs, "climb_meters"),
            RunRows.real(rs, "descent_meters"),
            rs.getString("route_type"),
            rs.getString("route_type_text"),
            rs.getInt("track_point_count"),
            RunRows.real(rs, "avg_speed_kmh"),
            RunRows.real(rs, "avg_pace_seconds"),
            RunRows.real(rs, "start_latitude"),
            RunRows.real(rs, "start_longitude"),
            RunRows.real(rs, "end_latitude"),
            RunRows.real(rs, "end_longitude"),
            RunRows.real(rs, "min_altitude_meters"),
            RunRows.real(rs, "max_altitude_meters"),
            rs.getString("source_file"),
            rs.getString("creator"));

    private static final RowMapper<TrackRow> TRACK_MAPPER = (rs, rowNum) -> new TrackRow(
            rs.getInt("seq"),
            RunRows.dateTime(rs.getString("timestamp")),
            RunRows.real(rs, "latitude"),
            RunRows.real(rs, "longitude"),
            RunRows.real(rs, "altitude"));

    /** 快照与轨迹缓存成对替换，保证 reload 后不会出现「新摘要 + 旧轨迹」的混搭。 */
    private record Snapshot(RunDataset dataset, ConcurrentHashMap<String, List<TrackPoint>> routes) {
    }

    private final JdbcClient jdbc;
    private final TcxIncrementalImporter importer;
    private final HealthDataProperties properties;
    private final SqliteSchema schema;

    private volatile Snapshot snapshot;
    private volatile SyncResult lastSync;

    public JdbcHealthDataRepository(JdbcClient jdbc,
                                    TcxIncrementalImporter importer,
                                    HealthDataProperties properties,
                                    SqliteSchema schema) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.properties = properties;
        this.schema = schema;
    }

    @Override
    public RunDataset dataset() {
        return current().dataset();
    }

    @Override
    public List<TrackPoint> route(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        String key = runId.trim();
        Snapshot current = current();
        List<TrackPoint> cached = current.routes().get(key);
        if (cached != null) {
            return cached;
        }
        List<TrackPoint> points = jdbc.sql(SELECT_TRACK_POINTS)
                .param(key)
                .query(TRACK_MAPPER)
                .list()
                .stream()
                .map(TrackRow::toTrackPoint)
                .toList();
        if (points.isEmpty()) {
            return List.of();
        }
        current.routes().putIfAbsent(key, points);
        return points;
    }

    @Override
    public synchronized void reload() {
        this.snapshot = load();
    }

    /** 最近一次同步的统计，{@code reload_data} 与 {@code describe_data_source} 用来展示导入细节。 */
    public SyncResult lastSync() {
        current();
        return lastSync == null ? SyncResult.empty(Instant.now()) : lastSync;
    }

    private Snapshot current() {
        Snapshot value = snapshot;
        if (value == null) {
            synchronized (this) {
                value = snapshot;
                if (value == null) {
                    value = load();
                    snapshot = value;
                }
            }
        }
        return value;
    }

    private Snapshot load() {
        schema.ensure();
        lastSync = importer.sync();

        List<ActivityRow> rows = jdbc.sql(SELECT_ACTIVITIES).query(ACTIVITY_MAPPER).list();
        List<RunActivity> activities = new ArrayList<>(rows.size());
        for (ActivityRow row : rows) {
            activities.add(RunRows.toActivity(row));
        }

        List<String> sourceFiles = activities.stream()
                .map(RunActivity::sourceFile)
                .distinct()
                .sorted()
                .toList();

        RunDataset dataset = new RunDataset(
                lastSync.syncedAt(),
                properties.dataDir(),
                sourceFiles,
                List.copyOf(activities),
                List.copyOf(lastSync.warnings()));

        log.info("快照已装载：{} 条活动、{} 个来源文件", activities.size(), sourceFiles.size());
        return new Snapshot(dataset, new ConcurrentHashMap<>());
    }
}
