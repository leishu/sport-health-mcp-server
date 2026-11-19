package org.example.sporthealth.repository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.importer.SourceFile;
import org.example.sporthealth.importer.huawei.ExcelSheet;
import org.example.sporthealth.importer.huawei.ExcelWorkbookParser;
import org.example.sporthealth.importer.huawei.HealthDetailParser;
import org.example.sporthealth.importer.huawei.HuaweiDataException;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner.ExportFile;
import org.example.sporthealth.importer.huawei.MetricSample;
import org.example.sporthealth.importer.huawei.MinuteActivityParser;
import org.example.sporthealth.importer.huawei.MinuteActivityRow;
import org.example.sporthealth.importer.huawei.MotionPathParser;
import org.example.sporthealth.importer.huawei.MotionTrackData.MotionStream;
import org.example.sporthealth.importer.huawei.ParsedWorkout;
import org.example.sporthealth.importer.huawei.SampleSequenceParser;
import org.example.sporthealth.importer.huawei.SampleSequenceRecord;
import org.example.sporthealth.repository.SourceFileStateStore.State;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 把华为运动健康 App 的全量导出（JSON）增量写进 SQLite。
 *
 * <p>和 {@link TcxIncrementalImporter} 并列：TCX 是一次一条活动的窄格式，JSON 导出才是
 * 华为给的全量数据（190 次运动 + 117 万条健康指标 + 10.7 万条分钟活动）。
 * 两者共用 {@code source_file} 指纹表，靠 {@code kind} 列区分，互不清理对方的记录。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>增量</b>：size + mtime 一致就完全不读文件；元数据不可用时退化为 SHA-256 比对。
 *       华为导出动辄 630MB，重复解析的代价不可接受；</li>
 *   <li><b>幂等</b>：一个文件一个事务，先按 {@code source_file} 删旧行再批量插入，
 *       中途失败整体回滚，不会留下半份数据；</li>
 *   <li><b>去重</b>：健康明细里同一批数据既有手机端副本又有平台副本（recordId 相同），
 *       靠 {@code health_metric} 主键 {@code (record_id, metric_key, start_time)} + INSERT OR IGNORE 吃掉；</li>
 *   <li><b>与 TCX 不重复计数</b>：10-25 ~ 11-04 的 6 次跑步两边都有。JSON 是超集（多出心率、
 *       步频、步数、热量），所以由 JSON 写入，TCX 那行连同轨迹一起删掉，天气改挂到新 id 上；</li>
 *   <li><b>批量写</b>：逐条 {@code update()} 写 100 万行要几分钟，这里用
 *       {@link NamedParameterJdbcTemplate#batchUpdate} 分批提交，事务边界仍是一个文件。</li>
 * </ul>
 */
@Component
@Slf4j
public class HuaweiExportImporter {

    /** 华为 JSON 与 TCX 判为同一次跑步的时间容差。 */
    private static final Duration SAME_ACTIVITY_TOLERANCE = Duration.ofMinutes(2);

    /** 单批插入行数：兼顾内存与 SQLite 的语句长度。 */
    private static final int BATCH_SIZE = 1000;

    /** 轨迹点单行更宽，批量小一些。 */
    private static final int TRACK_BATCH_SIZE = 500;

    private static final String INSERT_ACTIVITY = """
            INSERT INTO run_activity (
                id, activity_id, sport, run_date, start_time, end_time, start_time_utc,
                duration_seconds, distance_meters, climb_meters, descent_meters,
                route_type, route_type_text, track_point_count, avg_speed_kmh, avg_pace_seconds,
                start_latitude, start_longitude, end_latitude, end_longitude,
                min_altitude_meters, max_altitude_meters, source_file, creator,
                content_hash, imported_at, sport_family, sport_type_code, sub_type,
                total_steps, total_calories_kcal, avg_heart_rate, max_heart_rate, min_heart_rate,
                avg_cadence, record_id, source_kind, summary_data
            ) VALUES (
                :id, :activityId, :sport, :runDate, :startTime, :endTime, :startTimeUtc,
                :durationSeconds, :distanceMeters, :climbMeters, :descentMeters,
                :routeType, :routeTypeText, :trackPointCount, :avgSpeedKmh, :avgPaceSeconds,
                :startLatitude, :startLongitude, :endLatitude, :endLongitude,
                :minAltitudeMeters, :maxAltitudeMeters, :sourceFile, :creator,
                :contentHash, :importedAt, :sportFamily, :sportTypeCode, :subType,
                :totalSteps, :totalCaloriesKcal, :avgHeartRate, :maxHeartRate, :minHeartRate,
                :avgCadence, :recordId, 'huawei', :summaryData
            )
            """;

    private static final String INSERT_TRACK_POINT = """
            INSERT INTO track_point (
                run_id, seq, timestamp, latitude, longitude, altitude, heart_rate, cadence, distance_meters
            ) VALUES (
                :runId, :seq, :timestamp, :latitude, :longitude, :altitude, :heartRate, :cadence, :distanceMeters
            )
            """;

    private static final String INSERT_METRIC = """
            INSERT OR IGNORE INTO health_metric (
                record_id, metric_key, start_time, end_time, type_code,
                value_num, value_text, unit, device_code, time_zone, source_file
            ) VALUES (
                :recordId, :metricKey, :startTime, :endTime, :typeCode,
                :valueNum, :valueText, :unit, :deviceCode, :timeZone, :sourceFile
            )
            """;

    private static final String INSERT_MINUTE = """
            INSERT OR REPLACE INTO activity_minute (
                start_time, sport_type, record_day, end_time, steps, distance_meters,
                calories_kcal, duration_minutes, is_intensity, is_stand, altitude, device_code, source_file
            ) VALUES (
                :startTime, :sportType, :recordDay, :endTime, :steps, :distanceMeters,
                :caloriesKcal, :durationMinutes, :isIntensity, :isStand, :altitude, :deviceCode, :sourceFile
            )
            """;

    private static final String INSERT_SAMPLE = """
            INSERT OR REPLACE INTO sample_sequence (
                record_id, type_code, data_id, sub_type, start_time, end_time, time_zone,
                device_code, summary_data, detail_data, extend_data, source_file
            ) VALUES (
                :recordId, :typeCode, :dataId, :subType, :startTime, :endTime, :timeZone,
                :deviceCode, :summaryData, :detailData, :extendData, :sourceFile
            )
            """;

    private static final String INSERT_STREAM = """
            INSERT OR REPLACE INTO track_stream (
                run_id, stream_type, seq, time_millis, point_index, value, data
            ) VALUES (
                :runId, :streamType, :seq, :timeMillis, :pointIndex, :value, :data
            )
            """;

    private static final String INSERT_EXCEL_SHEET = """
            INSERT OR REPLACE INTO excel_sheet (
                source_file, sheet_name, row_count, column_count, header_json
            ) VALUES (
                :sourceFile, :sheetName, :rowCount, :columnCount, :headerJson
            )
            """;

    private static final String INSERT_EXCEL_ROW = """
            INSERT OR REPLACE INTO excel_sheet_row (
                source_file, sheet_name, row_number, row_data
            ) VALUES (
                :sourceFile, :sheetName, :rowNumber, :rowData
            )
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final HuaweiExportScanner scanner;
    private final MotionPathParser motionParser;
    private final HealthDetailParser healthParser;
    private final MinuteActivityParser minuteParser;
    private final SampleSequenceParser sampleSequenceParser;
    private final ExcelWorkbookParser excelParser;
    private final HealthDataProperties properties;
    private final SourceFileStateStore stateStore;
    private final SqliteSchema schema;
    private final DerivedTables derivedTables;

    public HuaweiExportImporter(DataSource dataSource,
                                JdbcClient jdbc,
                                TransactionOperations transactions,
                                HuaweiExportScanner scanner,
                                MotionPathParser motionParser,
                                HealthDetailParser healthParser,
                                MinuteActivityParser minuteParser,
                                SampleSequenceParser sampleSequenceParser,
                                ExcelWorkbookParser excelParser,
                                HealthDataProperties properties,
                                SourceFileStateStore stateStore,
                                SqliteSchema schema,
                                DerivedTables derivedTables) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scanner = scanner;
        this.motionParser = motionParser;
        this.healthParser = healthParser;
        this.minuteParser = minuteParser;
        this.sampleSequenceParser = sampleSequenceParser;
        this.excelParser = excelParser;
        this.properties = properties;
        this.stateStore = stateStore;
        this.schema = schema;
        this.derivedTables = derivedTables;
    }

    /**
     * 本次同步的统计。
     *
     * @param scanned           扫描到的 JSON 文件数
     * @param imported          本次真正解析并写入的文件数
     * @param unchanged         指纹命中、未读取内容的文件数
     * @param mergedDuplicates 因与 TCX 记录重合而由 JSON 接管的运动数
     * @param workouts          写入的运动记录数
     * @param metrics           写入（去重后）的健康指标采样点数
     * @param minutes           写入的分钟活动行数
     * @param sampleSequences   写入的样本序列行数
     * @param excelRows         写入的 Excel 数据行数
     * @param trackStreams      写入的轨迹附加流行数
     */
    public record Result(int scanned,
                         int imported,
                         int unchanged,
                         int mergedDuplicates,
                         int workouts,
                         int metrics,
                         int minutes,
                         int sampleSequences,
                         int excelRows,
                         int trackStreams,
                         List<String> warnings,
                         Instant syncedAt) {

        public static Result empty(Instant at) {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), at);
        }
    }

    /** 库里华为数据的规模，供 {@code describe_data_source} 展示。 */
    public record StorageStats(int workouts,
                               int runningWorkouts,
                               int otherWorkouts,
                               long healthMetrics,
                               long activityMinutes,
                               int metricTypes,
                               String firstMetricDate,
                               String lastMetricDate,
                               int bodyMeasurements,
                               int sleepSessions,
                               int activityDays,
                               int vitalDays,
                               long sampleSequences,
                               int sleepReports,
                               int ecgRecords,
                               int excelSheets,
                               int excelRows,
                               long trackStreams) {
    }

    /** 扫描数据目录并增量导入。 */
    public Result sync() {
        schema.ensure();
        List<String> warnings = new ArrayList<>();
        HuaweiExportScanner.ScanResult scan = scanner.scan(properties.dataDir());
        warnings.addAll(scan.problems());
        List<ExportFile> files = scan.files();
        if (files.isEmpty()) {
            return Result.empty(Instant.now());
        }

        Map<String, State> stored = stateStore.loadAll();
        List<TcxRun> tcxRuns = existingTcxRuns();
        int imported = 0;
        int unchanged = 0;
        int merged = 0;
        int workouts = 0;
        int metrics = 0;
        int minutes = 0;
        int sampleSequences = 0;
        int excelRows = 0;
        int trackStreams = 0;

        for (ExportFile entry : files) {
            SourceFile file = entry.file();
            State state = stored.get(file.name());
            try {
                if (state != null && state.sameMetadata(file.size(), file.modifiedMillis())) {
                    unchanged++;
                    reportStoredFailure(state, warnings);
                    continue;
                }
                String hash = file.sha256();
                if (state != null && hash.equals(state.hash())) {
                    stateStore.save(file.name(), file.size(), file.modifiedMillis(), hash,
                            state.failed(), state.errorMessage(), SourceFileStateStore.KIND_HUAWEI);
                    unchanged++;
                    reportStoredFailure(state, warnings);
                    continue;
                }
                Counters counters = switch (entry.kind()) {
                    case MOTION -> importMotion(file, hash, tcxRuns, warnings);
                    case HEALTH -> importHealth(file);
                    case MINUTE -> importMinute(file);
                    case SAMPLE_SEQUENCE -> importSampleSequence(file);
                    case SPORTSHEALTH_XLS, RESEARCH_XLSX -> importExcel(file);
                };
                stateStore.save(file.name(), file.size(), file.modifiedMillis(), hash,
                        false, null, SourceFileStateStore.KIND_HUAWEI);
                imported++;
                workouts += counters.workouts();
                metrics += counters.metrics();
                minutes += counters.minutes();
                sampleSequences += counters.sampleSequences();
                excelRows += counters.excelRows();
                trackStreams += counters.trackStreams();
                merged += counters.mergedDuplicates();
            } catch (HuaweiDataException e) {
                warn(warnings, file.name(), e.getMessage());
                safeSaveState(file, e.getMessage());
            } catch (Exception e) {
                warn(warnings, file.name(), e.getMessage());
                log.warn("导入华为导出文件失败 {}", file.name(), e);
            }
        }

        Set<String> names = new LinkedHashSet<>();
        for (ExportFile entry : files) {
            names.add(entry.file().name());
        }
        int removed = stateStore.deleteMissing(names, SourceFileStateStore.KIND_HUAWEI);
        if (removed > 0) {
            log.info("华为导出文件缺失 {} 个，已清理指纹（业务行按 source_file 保留）", removed);
        }

        // 原始长表写完之后重建四张派生表（体重 / 睡眠 / 每日活动 / 每日体征）。
        // 放在导入事务之外，且失败只降级成 warning：派生表坏了不影响已经落库的原始数据，
        // 下一次同步会自动重算。
        //
        // 派生表只由 health_metric / activity_minute 决定，所以「本次一个文件都没重导」时
        // 可以整段跳过，免得后台每 5 分钟空转还要跑一遍 17.8 万行分期上的窗口函数；
        // 表为空时（刚升级到 v7、或被人手工清过）无条件回填。
        if (imported > 0 || derivedTables.isEmpty()) {
            rebuildDerivedTables(warnings);
        }

        Instant syncedAt = Instant.now();
        log.info("华为导出同步完成：扫描 {} 个文件、重导 {}、未变 {}、由 JSON 接管 TCX {} 次；"
                        + "本次写入运动 {} 次、健康指标 {} 条、分钟活动 {} 行、样本序列 {} 条、"
                        + "Excel {} 行、轨迹附加流 {} 行",
                files.size(), imported, unchanged, merged, workouts, metrics, minutes,
                sampleSequences, excelRows, trackStreams);

        return new Result(files.size(), imported, unchanged, merged, workouts, metrics, minutes,
                sampleSequences, excelRows, trackStreams, List.copyOf(warnings), syncedAt);
    }

    /** 一次文件导入写入的行数。 */
    private record Counters(int workouts, int metrics, int minutes,
                            int sampleSequences, int excelRows, int trackStreams,
                            int mergedDuplicates) {
    }

    /** 重建派生表；任何异常都降级成一条 warning，不影响原始数据。 */
    private void rebuildDerivedTables(List<String> warnings) {
        try {
            derivedTables.rebuild();
        } catch (RuntimeException e) {
            log.warn("重建派生表失败", e);
            warnings.add("重建派生表失败：" + e.getMessage()
                    + "，原始数据不受影响，下次同步会重试");
        }
    }

    // ---------------------------------------------------------------- 运动轨迹

    /**
     * 导入一个运动 JSON 文件。
     *
     * <p>与 TCX 重合的运动不再跳过：JSON 是超集，由它写入，同时把 TCX 那行没掉
     * （{@link #takeOverTcx}），这样同一次跑步只有一条记录，且带心率、步频、步数。
     */
    private Counters importMotion(SourceFile file, String hash, List<TcxRun> tcxRuns, List<String> warnings)
            throws IOException {
        List<ParsedWorkout> parsed;
        try (InputStream input = file.open()) {
            parsed = motionParser.parse(file.name(), input, properties.zone());
        }

        Set<String> keptIds = new LinkedHashSet<>();
        List<ParsedWorkout> kept = new ArrayList<>(parsed.size());
        List<Map.Entry<ParsedWorkout, TcxRun>> takenOver = new ArrayList<>();
        Set<String> claimedTcx = new LinkedHashSet<>();
        for (ParsedWorkout workout : parsed) {
            TcxRun tcx = findTcx(tcxRuns, workout.startTime(), claimedTcx);
            if (tcx != null) {
                claimedTcx.add(tcx.id());
                takenOver.add(Map.entry(workout, tcx));
            }
            kept.add(workout);
            keptIds.add(workout.id());
        }
        if (!takenOver.isEmpty()) {
            warnings.add(file.name() + ": " + takenOver.size()
                    + " 次运动与 TCX 记录重合，已由 JSON 接管（TCX 行与轨迹删除，天气保留）");
        }
        rejectIdsOwnedByOtherFiles(file.name(), keptIds);

        String importedAt = Instant.now().toString();
        String sourceFile = file.name();
        int[] streamCounter = new int[1];
        transactions.executeWithoutResult(status -> {
            deleteWorkoutRows(sourceFile);
            for (Map.Entry<ParsedWorkout, TcxRun> entry : takenOver) {
                takeOverTcx(entry.getValue(), entry.getKey().id());
            }
            NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
            List<SqlParameterSource> activities = new ArrayList<>(BATCH_SIZE);
            List<SqlParameterSource> points = new ArrayList<>(TRACK_BATCH_SIZE);
            List<SqlParameterSource> streams = new ArrayList<>(TRACK_BATCH_SIZE);
            int streamCount = 0;
            for (ParsedWorkout workout : kept) {
                activities.add(activityParameters(workout, sourceFile, hash, importedAt));
                if (activities.size() >= BATCH_SIZE) {
                    flush(template, INSERT_ACTIVITY, activities);
                }
                appendTrackPoints(template, points, workout);
                streamCount += appendStreams(template, streams, workout);
            }
            flush(template, INSERT_ACTIVITY, activities);
            flush(template, INSERT_TRACK_POINT, points);
            flush(template, INSERT_STREAM, streams);
            streamCounter[0] = streamCount;
        });
        return new Counters(kept.size(), 0, 0, 0, 0, streamCounter[0], takenOver.size());
    }

    /**
     * 把未进轨迹点的流写进 {@code track_stream}。
     *
     * @return 本次追加的行数
     */
    private int appendStreams(NamedParameterJdbcTemplate template,
                              List<SqlParameterSource> batch,
                              ParsedWorkout workout) {
        int count = 0;
        Map<String, Integer> seqByType = new LinkedHashMap<>();
        for (MotionStream stream : workout.streams()) {
            int seq = seqByType.merge(stream.type(), 1, Integer::sum) - 1;
            batch.add(new MapSqlParameterSource()
                    .addValue("runId", workout.id())
                    .addValue("streamType", stream.type())
                    .addValue("seq", seq)
                    .addValue("timeMillis", stream.timeMillis())
                    .addValue("pointIndex", stream.pointIndex())
                    .addValue("value", stream.value())
                    .addValue("data", json(stream.fields().isEmpty()
                            ? Map.of("_raw", stream.rawLine())
                            : stream.fields())));
            count++;
            if (batch.size() >= TRACK_BATCH_SIZE) {
                flush(template, INSERT_STREAM, batch);
            }
        }
        return count;
    }

    private void appendTrackPoints(NamedParameterJdbcTemplate template,
                                   List<SqlParameterSource> batch,
                                   ParsedWorkout workout) {
        List<TrackPoint> trackPoints = workout.trackPoints();
        for (int seq = 0; seq < trackPoints.size(); seq++) {
            TrackPoint point = trackPoints.get(seq);
            batch.add(new MapSqlParameterSource()
                    .addValue("runId", workout.id())
                    .addValue("seq", seq)
                    .addValue("timestamp", RunRows.text(point.timestamp()))
                    .addValue("latitude", point.latitude())
                    .addValue("longitude", point.longitude())
                    .addValue("altitude", point.altitudeMeters())
                    .addValue("heartRate", point.heartRate())
                    .addValue("cadence", point.cadence())
                    .addValue("distanceMeters", point.distanceMeters()));
            if (batch.size() >= TRACK_BATCH_SIZE) {
                flush(template, INSERT_TRACK_POINT, batch);
            }
        }
    }

    /** 执行一批插入并返回真实受影响行数；{@code INSERT OR IGNORE} 丢掉的重复行不计入。 */
    private static int flush(NamedParameterJdbcTemplate template, String sql, List<SqlParameterSource> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int[] affected = template.batchUpdate(sql, batch.toArray(SqlParameterSource[]::new));
        batch.clear();
        int total = 0;
        for (int rows : affected) {
            total += Math.max(rows, 0);
        }
        return total;
    }

    private SqlParameterSource activityParameters(ParsedWorkout workout,
                                                  String sourceFile,
                                                  String hash,
                                                  String importedAt) {
        List<TrackPoint> points = workout.trackPoints();
        TrackPoint first = firstWithPosition(points, true);
        TrackPoint last = firstWithPosition(points, false);
        Double minAltitude = null;
        Double maxAltitude = null;
        for (TrackPoint point : points) {
            Double altitude = point.altitudeMeters();
            if (altitude == null) {
                continue;
            }
            minAltitude = minAltitude == null ? altitude : Math.min(minAltitude, altitude);
            maxAltitude = maxAltitude == null ? altitude : Math.max(maxAltitude, altitude);
        }
        boolean hasTrack = first != null;
        Double avgSpeedKmh = workout.durationSeconds() > 0 && workout.distanceMeters() > 0
                ? workout.distanceMeters() / workout.durationSeconds() * 3.6d
                : null;
        Double avgPace = workout.distanceMeters() > 0 && workout.durationSeconds() > 0
                ? workout.durationSeconds() / (workout.distanceMeters() / 1000d)
                : null;
        return new MapSqlParameterSource()
                .addValue("id", workout.id())
                .addValue("activityId", workout.startTime().toString())
                .addValue("sport", workout.sport())
                .addValue("runDate", workout.startTime().atZone(properties.zone()).toLocalDate().toString())
                .addValue("startTime", RunRows.text(LocalDateTime.ofInstant(workout.startTime(), properties.zone())))
                .addValue("endTime", RunRows.text(LocalDateTime.ofInstant(workout.endTime(), properties.zone())))
                .addValue("startTimeUtc", workout.startTime().toString())
                .addValue("durationSeconds", workout.durationSeconds())
                .addValue("distanceMeters", workout.distanceMeters())
                .addValue("climbMeters", workout.climbMeters())
                .addValue("descentMeters", workout.descentMeters())
                .addValue("routeType", hasTrack ? "0" : "1")
                .addValue("routeTypeText", hasTrack ? "OUTDOOR" : "INDOOR")
                .addValue("trackPointCount", points.size())
                .addValue("avgSpeedKmh", avgSpeedKmh)
                .addValue("avgPaceSeconds", avgPace)
                .addValue("startLatitude", first == null ? null : first.latitude())
                .addValue("startLongitude", first == null ? null : first.longitude())
                .addValue("endLatitude", last == null ? null : last.latitude())
                .addValue("endLongitude", last == null ? null : last.longitude())
                .addValue("minAltitudeMeters", minAltitude)
                .addValue("maxAltitudeMeters", maxAltitude)
                .addValue("sourceFile", sourceFile)
                .addValue("creator", "HUAWEI_HEALTH")
                .addValue("contentHash", hash)
                .addValue("importedAt", importedAt)
                .addValue("sportFamily", workout.sportFamily())
                .addValue("sportTypeCode", workout.sportTypeCode())
                .addValue("subType", workout.subType())
                .addValue("totalSteps", workout.totalSteps())
                .addValue("totalCaloriesKcal", workout.caloriesKcal())
                .addValue("avgHeartRate", workout.avgHeartRate())
                .addValue("maxHeartRate", workout.maxHeartRate())
                .addValue("minHeartRate", workout.minHeartRate())
                .addValue("avgCadence", workout.avgCadence())
                .addValue("recordId", workout.recordId())
                .addValue("summaryData", workout.summaryData());
    }

    private static TrackPoint firstWithPosition(List<TrackPoint> points, boolean fromStart) {
        if (fromStart) {
            for (TrackPoint point : points) {
                if (point.hasPosition()) {
                    return point;
                }
            }
            return null;
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).hasPosition()) {
                return points.get(i);
            }
        }
        return null;
    }

    /** 一条已入库的 TCX 记录：接管时用 id 删行、用 source_file 清指纹。 */
    private record TcxRun(String id, String sourceFile, Instant startUtc) {
    }

    /** 在 ±2 分钟内找对应的 TCX 记录；同一份 JSON 里已经接管过的不再重复匹配。 */
    private static TcxRun findTcx(List<TcxRun> tcxRuns, Instant start, Set<String> claimed) {
        for (TcxRun candidate : tcxRuns) {
            if (claimed.contains(candidate.id())) {
                continue;
            }
            if (Math.abs(Duration.between(candidate.startUtc(), start).getSeconds())
                    <= SAME_ACTIVITY_TOLERANCE.getSeconds()) {
                return candidate;
            }
        }
        return null;
    }

    private List<TcxRun> existingTcxRuns() {
        return jdbc.sql("SELECT id, source_file, start_time_utc FROM run_activity WHERE source_kind = 'tcx'")
                .query((rs, rowNum) -> {
                    try {
                        return new TcxRun(rs.getString("id"), rs.getString("source_file"),
                                Instant.parse(rs.getString("start_time_utc")));
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 同一次跑步两边都有：JSON 接管，TCX 让位。
     *
     * <p>天气是外部接口按起终点坐标查的，与文件格式无关，直接改挂到新 id 上；
     * 若新 id 上已有天气行（上一次接管留下的），保留它、删掉 TCX 的残留。
     *
     * <p>TCX 的指纹也一并删掉：下一次同步会重跑这个 TCX，走到 TCX 侧的「让位」规则再重新登记指纹，
     * 这样指纹表不会指向一条已经不存在的记录。
     */
    private void takeOverTcx(TcxRun tcx, String newId) {
        jdbc.sql("UPDATE OR IGNORE run_weather SET run_id = :newId WHERE run_id = :oldId")
                .param("newId", newId)
                .param("oldId", tcx.id())
                .update();
        jdbc.sql("DELETE FROM run_weather WHERE run_id = :oldId").param("oldId", tcx.id()).update();
        jdbc.sql("DELETE FROM track_point WHERE run_id = :oldId").param("oldId", tcx.id()).update();
        jdbc.sql("DELETE FROM track_stream WHERE run_id = :oldId").param("oldId", tcx.id()).update();
        jdbc.sql("DELETE FROM run_activity WHERE id = :oldId").param("oldId", tcx.id()).update();
        jdbc.sql("DELETE FROM source_file WHERE source_file = :file AND kind = 'tcx'")
                .param("file", tcx.sourceFile())
                .update();
    }

    /** 已入库的华为运动开始时刻，TCX 侧用它判断这次活动是否已经由 JSON 覆盖。 */
    public List<Instant> huaweiRunStarts() {
        return jdbc.sql("SELECT start_time_utc FROM run_activity WHERE source_kind = 'huawei'")
                .query(String.class)
                .list()
                .stream()
                .map(text -> {
                    try {
                        return Instant.parse(text);
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** 同一个 id 只允许属于一个来源文件，被占用时整份文件拒绝导入。 */
    private void rejectIdsOwnedByOtherFiles(String sourceFile, Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> foreign = jdbc.sql("""
                SELECT id FROM run_activity
                WHERE source_file <> :sourceFile AND id IN (:ids)
                ORDER BY id
                """)
                .param("sourceFile", sourceFile)
                .param("ids", ids)
                .query(String.class)
                .list();
        if (!foreign.isEmpty()) {
            throw new HuaweiDataException("运动 id 已被其它来源文件占用: " + foreign);
        }
    }

    /** 重导前清掉这个文件上次写入的活动、轨迹与天气行。 */
    private void deleteWorkoutRows(String sourceFile) {
        List<String> ids = jdbc.sql("SELECT id FROM run_activity WHERE source_file = ?")
                .param(sourceFile)
                .query(String.class)
                .list();
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM track_point WHERE run_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM track_stream WHERE run_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM run_weather WHERE run_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM run_activity WHERE id IN (:ids)").param("ids", ids).update();
    }

    // ---------------------------------------------------------------- 健康明细

    private Counters importHealth(SourceFile file) {
        String sourceFile = file.name();
        long[] delta = new long[1];
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM health_metric WHERE source_file = ?").param(sourceFile).update();
            long before = longCount("SELECT COUNT(*) FROM health_metric");
            NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
            List<SqlParameterSource> batch = new ArrayList<>(BATCH_SIZE);
            try (InputStream input = file.open()) {
                healthParser.parse(file.name(), input, properties.zone(), sample -> {
                    batch.add(metricParameters(sample, sourceFile));
                    if (batch.size() >= BATCH_SIZE) {
                        flush(template, INSERT_METRIC, batch);
                    }
                });
            } catch (IOException e) {
                throw new HuaweiDataException(file.name() + ": 读取失败 -> " + e.getMessage(), e);
            }
            flush(template, INSERT_METRIC, batch);
            delta[0] = longCount("SELECT COUNT(*) FROM health_metric") - before;
        });
        return new Counters(0, (int) delta[0], 0, 0, 0, 0, 0);
    }

    private SqlParameterSource metricParameters(MetricSample sample, String sourceFile) {
        return new MapSqlParameterSource()
                .addValue("recordId", sample.recordId())
                .addValue("metricKey", sample.metricKey())
                .addValue("startTime", RunRows.text(sample.startTime()))
                .addValue("endTime", RunRows.text(sample.endTime()))
                .addValue("typeCode", sample.typeCode())
                .addValue("valueNum", sample.valueNum())
                .addValue("valueText", sample.valueText())
                .addValue("unit", sample.unit())
                .addValue("deviceCode", sample.deviceCode())
                .addValue("timeZone", sample.timeZone())
                .addValue("sourceFile", sourceFile);
    }

    // ---------------------------------------------------------------- 分钟活动

    private Counters importMinute(SourceFile file) {
        String sourceFile = file.name();
        long[] delta = new long[1];
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM activity_minute WHERE source_file = ?").param(sourceFile).update();
            long before = longCount("SELECT COUNT(*) FROM activity_minute");
            NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
            List<SqlParameterSource> batch = new ArrayList<>(BATCH_SIZE);
            try (InputStream input = file.open()) {
                minuteParser.parse(file.name(), input, properties.zone(), row -> {
                    batch.add(minuteParameters(row, sourceFile));
                    if (batch.size() >= BATCH_SIZE) {
                        flush(template, INSERT_MINUTE, batch);
                    }
                });
            } catch (IOException e) {
                throw new HuaweiDataException(file.name() + ": 读取失败 -> " + e.getMessage(), e);
            }
            flush(template, INSERT_MINUTE, batch);
            delta[0] = longCount("SELECT COUNT(*) FROM activity_minute") - before;
        });
        return new Counters(0, 0, (int) delta[0], 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- 样本序列

    private Counters importSampleSequence(SourceFile file) {
        String sourceFile = file.name();
        int[] inserted = new int[1];
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM sample_sequence WHERE source_file = ?").param(sourceFile).update();
            NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
            List<SqlParameterSource> batch = new ArrayList<>(200);
            try (InputStream input = file.open()) {
                sampleSequenceParser.parse(sourceFile, input, record -> {
                    batch.add(sampleParameters(record, sourceFile));
                    inserted[0]++;
                    if (batch.size() >= 200) {
                        flush(template, INSERT_SAMPLE, batch);
                    }
                });
            } catch (IOException e) {
                throw new HuaweiDataException(sourceFile + ": 读取失败 -> " + e.getMessage(), e);
            }
            flush(template, INSERT_SAMPLE, batch);
        });
        return new Counters(0, 0, 0, inserted[0], 0, 0, 0);
    }

    private SqlParameterSource sampleParameters(SampleSequenceRecord record, String sourceFile) {
        return new MapSqlParameterSource()
                .addValue("recordId", record.recordId())
                .addValue("typeCode", record.typeCode())
                .addValue("dataId", record.dataId())
                .addValue("subType", record.subType())
                .addValue("startTime", RunRows.text(java.time.LocalDateTime.ofInstant(
                        record.startTime(), properties.zone())))
                .addValue("endTime", RunRows.text(java.time.LocalDateTime.ofInstant(
                        record.endTime(), properties.zone())))
                .addValue("timeZone", record.timeZone())
                .addValue("deviceCode", record.deviceCode())
                .addValue("summaryData", record.summaryData())
                .addValue("detailData", record.detailData())
                .addValue("extendData", record.extendData())
                .addValue("sourceFile", sourceFile);
    }

    // ---------------------------------------------------------------- Excel

    private Counters importExcel(SourceFile file) {
        String sourceFile = file.name();
        List<ExcelSheet> sheets;
        try (InputStream input = file.open()) {
            sheets = excelParser.parse(sourceFile, input);
        } catch (IOException e) {
            throw new HuaweiDataException(sourceFile + ": 读取失败 -> " + e.getMessage(), e);
        }

        int[] inserted = new int[1];
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM excel_sheet_row WHERE source_file = ?").param(sourceFile).update();
            jdbc.sql("DELETE FROM excel_sheet WHERE source_file = ?").param(sourceFile).update();
            NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
            List<SqlParameterSource> sheetBatch = new ArrayList<>();
            List<SqlParameterSource> rowBatch = new ArrayList<>(BATCH_SIZE);
            for (ExcelSheet sheet : sheets) {
                sheetBatch.add(new MapSqlParameterSource()
                        .addValue("sourceFile", sourceFile)
                        .addValue("sheetName", sheet.name())
                        .addValue("rowCount", sheet.rows().size())
                        .addValue("columnCount", sheet.headers().size())
                        .addValue("headerJson", json(sheet.headers())));
                for (int row = 0; row < sheet.rows().size(); row++) {
                    rowBatch.add(new MapSqlParameterSource()
                            .addValue("sourceFile", sourceFile)
                            .addValue("sheetName", sheet.name())
                            .addValue("rowNumber", row + 1)
                            .addValue("rowData", rowJson(sheet.headers(), sheet.rows().get(row))));
                    inserted[0]++;
                    if (rowBatch.size() >= BATCH_SIZE) {
                        flush(template, INSERT_EXCEL_ROW, rowBatch);
                    }
                }
            }
            template.batchUpdate(INSERT_EXCEL_SHEET, sheetBatch.toArray(SqlParameterSource[]::new));
            flush(template, INSERT_EXCEL_ROW, rowBatch);
        });
        return new Counters(0, 0, 0, 0, inserted[0], 0, 0);
    }

    /** 行对象只放非空单元格：空值用 {@code json_extract} 取出来同样是 NULL。 */
    private static String rowJson(List<String> headers, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < headers.size() && index < values.size(); index++) {
            String value = values.get(index);
            if (value != null && !value.isEmpty()) {
                row.put(headers.get(index), value);
            }
        }
        return json(row);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new HuaweiDataException("序列化 JSON 失败 -> " + e.getMessage(), e);
        }
    }

    private SqlParameterSource minuteParameters(MinuteActivityRow row, String sourceFile) {
        return new MapSqlParameterSource()
                .addValue("startTime", RunRows.text(row.startTime()))
                .addValue("sportType", row.sportType())
                .addValue("recordDay", row.recordDay())
                .addValue("endTime", RunRows.text(row.endTime()))
                .addValue("steps", row.steps())
                .addValue("distanceMeters", row.distanceMeters())
                .addValue("caloriesKcal", row.caloriesKcal())
                .addValue("durationMinutes", row.durationMinutes())
                .addValue("isIntensity", row.isIntensity())
                .addValue("isStand", row.isStand())
                .addValue("altitude", row.altitude())
                .addValue("deviceCode", row.deviceCode())
                .addValue("sourceFile", sourceFile);
    }

    // ---------------------------------------------------------------- 公共

    /** 库里华为数据的规模；表为空时各项为 0。 */
    public StorageStats stats() {
        schema.ensure();
        int workouts = count("SELECT COUNT(*) FROM run_activity WHERE source_kind = 'huawei'");
        int running = count("SELECT COUNT(*) FROM run_activity WHERE source_kind = 'huawei' AND sport_family = 'RUN'");
        long metrics = longCount("SELECT COUNT(*) FROM health_metric");
        long minutes = longCount("SELECT COUNT(*) FROM activity_minute");
        int types = count("SELECT COUNT(*) FROM health_metric_type");
        DerivedTables.Counts derived = derivedTables.counts();
        return new StorageStats(workouts, running, workouts - running, metrics, minutes, types,
                text("SELECT MIN(substr(start_time, 1, 10)) FROM health_metric"),
                text("SELECT MAX(substr(start_time, 1, 10)) FROM health_metric"),
                derived.bodyMeasurements(), derived.sleepSessions(),
                derived.activityDays(), derived.vitalDays(),
                longCount("SELECT COUNT(*) FROM sample_sequence"),
                count("SELECT COUNT(*) FROM sleep_report"),
                count("SELECT COUNT(*) FROM ecg_record"),
                count("SELECT COUNT(*) FROM excel_sheet"),
                count("SELECT COUNT(*) FROM excel_sheet_row"),
                longCount("SELECT COUNT(*) FROM track_stream"));
    }

    private int count(String sql) {
        Integer value = jdbc.sql(sql).query(Integer.class).optional().orElse(0);
        return value == null ? 0 : value;
    }

    private long longCount(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).optional().orElse(0L);
        return value == null ? 0L : value;
    }

    private String text(String sql) {
        return jdbc.sql(sql).query(String.class).optional().orElse(null);
    }

    private static void reportStoredFailure(State state, List<String> warnings) {
        if (state.failed() && state.errorMessage() != null) {
            warnings.add(state.name() + ": " + state.errorMessage());
        }
    }

    private void safeSaveState(SourceFile file, String errorMessage) {
        try {
            stateStore.save(file.name(), file.size(), file.modifiedMillis(), hashOf(file),
                    true, errorMessage, SourceFileStateStore.KIND_HUAWEI);
        } catch (RuntimeException e) {
            log.warn("记录华为导出文件状态失败 {}", file.name(), e);
        }
    }

    private String hashOf(SourceFile file) {
        try {
            return file.sha256();
        } catch (Exception e) {
            return null;
        }
    }

    private void warn(List<String> warnings, String fileName, String message) {
        warnings.add(fileName + ": " + message);
        log.warn("跳过无法解析的华为导出文件 {}: {}", fileName, message);
    }
}
