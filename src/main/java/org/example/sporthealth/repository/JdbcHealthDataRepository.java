package org.example.sporthealth.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
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
                   min_altitude_meters, max_altitude_meters, source_file, creator,
                   avg_heart_rate, max_heart_rate, min_heart_rate, avg_cadence,
                   total_steps, total_calories_kcal, record_id, sport_type_code, sub_type, summary_data
            FROM run_activity
            WHERE sport_family = 'RUN'
            ORDER BY start_time DESC
            """;

    private static final String SELECT_TRACK_POINTS = """
            SELECT seq, timestamp, latitude, longitude, altitude, heart_rate, cadence, distance_meters
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
            rs.getString("creator"),
            RunRows.integer(rs, "avg_heart_rate"),
            RunRows.integer(rs, "max_heart_rate"),
            RunRows.integer(rs, "min_heart_rate"),
            RunRows.integer(rs, "avg_cadence"),
            RunRows.integer(rs, "total_steps"),
            RunRows.real(rs, "total_calories_kcal"),
            rs.getString("record_id"),
            RunRows.integer(rs, "sport_type_code"),
            RunRows.integer(rs, "sub_type"),
            rs.getString("summary_data"));

    private static final String SELECT_STREAMS = """
            SELECT run_id, stream_type, seq, time_millis, point_index, value, data
            FROM track_stream
            WHERE run_id = ?
            ORDER BY stream_type, seq
            """;

    private static final String SELECT_STREAMS_BY_TYPE = """
            SELECT run_id, stream_type, seq, time_millis, point_index, value, data
            FROM track_stream
            WHERE run_id = ? AND stream_type = ?
            ORDER BY seq
            """;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> FIELD_TYPE = new TypeReference<>() {
    };

    private static final RowMapper<TrackRow> TRACK_MAPPER = (rs, rowNum) -> new TrackRow(
            rs.getInt("seq"),
            RunRows.dateTime(rs.getString("timestamp")),
            RunRows.real(rs, "latitude"),
            RunRows.real(rs, "longitude"),
            RunRows.real(rs, "altitude"),
            RunRows.integer(rs, "heart_rate"),
            RunRows.integer(rs, "cadence"),
            RunRows.real(rs, "distance_meters"));

    private static final RowMapper<TrackStream> STREAM_MAPPER = (rs, rowNum) -> new TrackStream(
            rs.getString("run_id"),
            rs.getString("stream_type"),
            rs.getInt("seq"),
            RunRows.real(rs, "time_millis") == null ? null : rs.getLong("time_millis"),
            RunRows.integer(rs, "point_index"),
            RunRows.real(rs, "value"),
            parseFields(rs.getString("data")));

    /** {@code data} 是本项目自己写的 JSON 对象；解析失败说明库被外部改过，退化成 {@code _raw}。 */
    private static Map<String, String> parseFields(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(JSON.readValue(data, FIELD_TYPE));
        } catch (Exception e) {
            log.warn("track_stream.data 不是合法 JSON，按原文保留：{}", e.getMessage());
            return Map.of("_raw", data);
        }
    }

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

    /**
     * 原始流直查：{@code track_stream} 按主键前缀 {@code (run_id, stream_type)} 命中，
     * 不需要轨迹点，也不走缓存（数据量小、调用频率低，实时读更简单）。
     */
    @Override
    public List<TrackStream> streams(String runId, String streamType) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        String key = runId.trim();
        if (streamType == null || streamType.isBlank()) {
            return jdbc.sql(SELECT_STREAMS).param(key).query(STREAM_MAPPER).list();
        }
        return jdbc.sql(SELECT_STREAMS_BY_TYPE)
                .param(key)
                .param(streamType.trim())
                .query(STREAM_MAPPER)
                .list();
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

    /** 最近一次华为导出同步的统计。 */
    public HuaweiExportImporter.Result lastHuaweiSync() {
        current();
        return importer.lastHuaweiSync();
    }

    /** 华为数据的规模（运动、健康指标、分钟活动），供 {@code describe_data_source} 展示。 */
    public HuaweiExportImporter.StorageStats healthStats() {
        return importer.huaweiStats();
    }

    /**
     * 库里全部运动记录数（含步行等非跑步项目），与只含 {@code sport_family='RUN'} 的跑步条数并列展示。
     *
     * <p>不能用华为导出的运动数顶替：TCX 独有、没被 JSON 接管的记录不在那个统计里。
     */
    public int workoutCount() {
        current();
        return jdbc.sql("SELECT COUNT(*) FROM run_activity").query(Integer.class).optional().orElse(0);
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
