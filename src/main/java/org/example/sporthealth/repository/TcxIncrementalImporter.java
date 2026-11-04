package org.example.sporthealth.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.importer.ParsedRun;
import org.example.sporthealth.importer.SourceFile;
import org.example.sporthealth.importer.SourceFileScanner;
import org.example.sporthealth.importer.TcxDataException;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.RunRows.SyncResult;
import org.example.sporthealth.repository.SourceFileStateStore.State;
import org.example.sporthealth.weather.WeatherBackfill;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 把扫描到的 TCX 文件增量同步进 SQLite，是导入过程的唯一写入口。
 *
 * <p>判断规则（按代价从低到高）：
 * <ol>
 *   <li>库中没有该文件 → 算 SHA-256、解析、写入；</li>
 *   <li>{@code size} 与 {@code mtime} 都和上次记录一致 → 视为未变化，<b>不读文件内容</b>；</li>
 *   <li>元数据对不上（含元数据不可用，如 jar 内资源）→ 算 SHA-256：与上次一致只是被 touch，
 *       跳过；不一致才真正重新解析。</li>
 * </ol>
 *
 * <p>写入语义：
 * <ul>
 *   <li>一个文件的所有 Activity 与轨迹在<b>同一个事务</b>内完成「删除旧行 → 批量插入」，中途失败整体回滚；</li>
 *   <li>解析在事务之前完成，解析失败不会动库里的旧数据，失败原因写入 {@code source_file.status}，
 *       文件内容不变时不再重试，但每次同步都把这个原因重新放进 warnings；</li>
 *   <li>源文件被删除时历史记录<b>保留</b>——这是持久化的意义所在，只把来源状态标记为缺失；</li>
 *   <li>同一个记录 id 只允许属于一个来源文件，被其它文件占用时拒绝导入，不静默覆盖别人的数据。</li>
 * </ul>
 */
@Component
@Slf4j
public class TcxIncrementalImporter {

    private static final String INSERT_ACTIVITY = """
            INSERT INTO run_activity (
                id, activity_id, sport, run_date, start_time, end_time, start_time_utc,
                duration_seconds, distance_meters, climb_meters, descent_meters,
                route_type, route_type_text, track_point_count, avg_speed_kmh, avg_pace_seconds,
                start_latitude, start_longitude, end_latitude, end_longitude,
                min_altitude_meters, max_altitude_meters, source_file, creator,
                content_hash, imported_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;

    private static final String INSERT_TRACK_POINT = """
            INSERT INTO track_point (run_id, seq, timestamp, latitude, longitude, altitude)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final SourceFileScanner scanner;
    private final TcxParser parser;
    private final HealthDataProperties properties;
    private final SourceFileStateStore stateStore;
    private final SqliteSchema schema;
    private final WeatherBackfill weatherBackfill;

    public TcxIncrementalImporter(JdbcClient jdbc,
                                  TransactionOperations transactions,
                                  SourceFileScanner scanner,
                                  TcxParser parser,
                                  HealthDataProperties properties,
                                  SourceFileStateStore stateStore,
                                  SqliteSchema schema,
                                  WeatherBackfill weatherBackfill) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scanner = scanner;
        this.parser = parser;
        this.properties = properties;
        this.stateStore = stateStore;
        this.schema = schema;
        this.weatherBackfill = weatherBackfill;
    }

    /** 扫描数据目录并把变化写入库，返回本次同步的统计。 */
    public SyncResult sync() {
        schema.ensure();
        List<String> warnings = new ArrayList<>();

        SourceFileScanner.ScanResult scan = scanner.scan(properties.dataDir());
        warnings.addAll(scan.problems());
        List<SourceFile> files = scan.files();
        if (files.isEmpty()) {
            warnings.add("数据目录 " + properties.dataDir() + " 下没有找到 *.tcx 文件");
        }

        Map<String, State> stored = stateStore.loadAll();
        int imported = 0;
        int unchanged = 0;
        for (SourceFile file : files) {
            State state = stored.get(file.name());
            try {
                if (state != null && state.sameMetadata(file.size(), file.modifiedMillis())) {
                    unchanged++;
                    reportStoredFailure(state, warnings);
                    continue;
                }
                String hash = file.sha256();
                if (state != null && hash.equals(state.hash())) {
                    // 内容没变，只是被 touch 或元数据不可用：只刷新元数据，不碰业务行
                    stateStore.save(file.name(), file.size(), file.modifiedMillis(), hash,
                            state.failed(), state.errorMessage());
                    unchanged++;
                    reportStoredFailure(state, warnings);
                    continue;
                }
                importFile(file, hash, warnings);
                stateStore.save(file.name(), file.size(), file.modifiedMillis(), hash, false, null);
                imported++;
            } catch (TcxDataException e) {
                warn(warnings, file.name(), e.getMessage());
                // 解析失败也记下元数据 + 哈希：文件不改动就不再反复重试，但原因要留存
                safeSaveState(file, hashOf(file), e.getMessage());
            } catch (Exception e) {
                warn(warnings, file.name(), e.getMessage());
                log.warn("同步 TCX 文件失败 {}", file.name(), e);
            }
        }

        // 目录为空多半是路径配错或挂载点没就绪：保留指纹，避免恢复后把全部文件重导一遍
        Set<String> scanned = names(files);
        int removedStates = files.isEmpty() ? 0 : stateStore.deleteMissing(scanned);
        List<String> orphaned = stateStore.orphanedFiles(scanned);
        if (removedStates > 0 || !orphaned.isEmpty()) {
            log.info("来源文件缺失 {} 个（清理指纹 {} 条），历史记录保留", orphaned.size(), removedStates);
        }

        Instant syncedAt = Instant.now();
        log.info("增量同步完成：扫描 {} 个文件、重导 {} 个、未变 {} 个，库中活动 {} 条",
                files.size(), imported, unchanged, activityCount());

        // 天气回填放在导入事务全部结束之后：HTTP 调用不能占用池里唯一的连接。
        // 失败只进 warnings，绝不影响已经写好的跑步数据。
        appendWeather(syncedAt, warnings);

        return new SyncResult(files.size(), imported, unchanged, orphaned, warnings, syncedAt);
    }

    /**
     * 回填缺失天气，把失败原因汇总成一条 warning。
     *
     * <p>按条数汇总而不是逐条列出：一次网络故障会让所有记录都失败，
     * 把几十条同样的错误塞进 warnings 只会淹没真正有用的信息。
     *
     * <p>「延后」单独成一条：它不是失败，而是接口连续不可用后本轮主动收尾，
     * 这些记录没有落行、下次同步会自动重试。不写出来，用户只能看到「本次没补全」却查不到原因。
     */
    private void appendWeather(Instant syncedAt, List<String> warnings) {
        try {
            WeatherBackfill.Result result = weatherBackfill.run(syncedAt);
            if (result.failed() > 0) {
                String reason = result.retryableProblems().isEmpty()
                        ? "未知原因" : result.retryableProblems().get(0);
                warnings.add("天气回填失败 " + result.failed() + " 条（" + reason
                        + "），将在下次同步重试");
            }
            if (result.deferred() > 0) {
                warnings.add("天气回填本轮提前结束：接口连续失败，" + result.deferred()
                        + " 条留待下次同步");
            }
        } catch (RuntimeException e) {
            log.warn("天气回填异常，已忽略", e);
            warnings.add("天气回填异常：" + e.getMessage() + "，不影响跑步数据");
        }
    }

    /** 一个文件的完整写入：解析在前，事务内删除旧行 + 批量插入。 */
    private void importFile(SourceFile file, String hash, List<String> warnings) throws Exception {
        List<ParsedRun> runs;
        try (var input = file.open()) {
            runs = parser.parse(file.name(), input);
        }

        Set<String> ids = new LinkedHashSet<>();
        for (ParsedRun run : runs) {
            ids.add(run.activity().id());
            run.warnings().forEach(warning -> warnings.add(file.name() + ": " + warning));
        }
        if (ids.size() != runs.size()) {
            throw new TcxDataException("文件内记录 id 重复: " + ids);
        }
        rejectIdsOwnedByOtherFiles(file.name(), ids);

        String importedAt = Instant.now().toString();
        String sourceFile = file.name();
        transactions.executeWithoutResult(status -> {
            Map<String, WeatherKey> previous = jdbc.sql("""
                            SELECT id, start_time_utc, duration_seconds,
                                   start_latitude, start_longitude, end_latitude, end_longitude
                            FROM run_activity
                            WHERE source_file = ?
                            """)
                    .param(sourceFile)
                    .query((rs, rowNum) -> new StoredWeatherKey(
                            rs.getString("id"),
                            WeatherKey.of(
                                    Instant.parse(rs.getString("start_time_utc")),
                                    rs.getDouble("duration_seconds"),
                                    RunRows.real(rs, "start_latitude"),
                                    RunRows.real(rs, "start_longitude"),
                                    RunRows.real(rs, "end_latitude"),
                                    RunRows.real(rs, "end_longitude"))))
                    .list()
                    .stream()
                    .collect(Collectors.toMap(StoredWeatherKey::id, StoredWeatherKey::key,
                            (left, right) -> left, LinkedHashMap::new));

            Set<String> existing = new LinkedHashSet<>(previous.keySet());
            // 本次解析后不再存在的 id（例如同一 TCX 里的 Activity 被删掉）：它们的天气行也得跟着走
            Set<String> staleWeather = new LinkedHashSet<>(existing);
            staleWeather.removeAll(ids);
            // id 没变但影响天气查询的字段变了（起跑时刻、时长、起终点坐标）：
            // 例如同名文件被换成另一次跑步，旧天气不能再算到新记录头上
            for (ParsedRun run : runs) {
                WeatherKey before = previous.get(run.activity().id());
                if (before != null && !before.equals(WeatherKey.of(run.activity()))) {
                    staleWeather.add(run.activity().id());
                }
            }

            existing.addAll(ids);
            deleteRows(existing, staleWeather);
            for (ParsedRun run : runs) {
                insertActivity(run, sourceFile, hash, importedAt);
                insertTrackPoints(run);
            }
        });
    }

    /**
     * 决定一条记录的天气是否还有效的字段。
     *
     * <p>id 是文件名（或文件名 + 序号），只靠它判断「还是同一次跑步」并不可靠：
     * 同名文件被替换成另一次活动、或起跑时刻 / 坐标被修正时，旧天气必须作废。
     * 距离、爬升、轨迹点数量不影响天气查询，不参与比较，避免无谓地重拉。
     */
    private record WeatherKey(Instant startTimeUtc,
                              long durationSeconds,
                              Double startLatitude,
                              Double startLongitude,
                              Double endLatitude,
                              Double endLongitude) {

        static WeatherKey of(RunActivity activity) {
            return of(activity.startTimeUtc(), activity.durationSeconds(),
                    activity.startLatitude(), activity.startLongitude(),
                    activity.endLatitude(), activity.endLongitude());
        }

        static WeatherKey of(Instant startTimeUtc, double durationSeconds,
                             Double startLatitude, Double startLongitude,
                             Double endLatitude, Double endLongitude) {
            // 结束时刻 = 起跑 + 总时长（四舍五入到秒），与 WeatherBackfill.endOf 的口径一致；
            // 终点坐标缺失时退回起点，与 SELECT_PENDING 的 COALESCE 一致，避免无谓地重拉
            Double effectiveEndLatitude = endLatitude != null ? endLatitude : startLatitude;
            Double effectiveEndLongitude = endLongitude != null ? endLongitude : startLongitude;
            return new WeatherKey(startTimeUtc, Math.round(durationSeconds),
                    startLatitude, startLongitude, effectiveEndLatitude, effectiveEndLongitude);
        }
    }

    /** 查询结果里的一行：id + 旧的天气指纹。 */
    private record StoredWeatherKey(String id, WeatherKey key) {
    }

    /**
     * 同一个 id 只能属于一个来源文件。文件改名后旧行会留在库里，
     * 若直接覆盖等于静默删掉另一份记录，因此先查一遍归属。
     */
    private void rejectIdsOwnedByOtherFiles(String sourceFile, Set<String> ids) {
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
            throw new TcxDataException("记录 id 已被其它来源文件占用: " + foreign);
        }
    }

    /** 文件没变就不会重试，但上次的失败必须继续进 warnings，否则数据缺口会静默消失。 */
    private static void reportStoredFailure(State state, List<String> warnings) {
        if (state.failed() && state.errorMessage() != null) {
            warnings.add(state.name() + ": " + state.errorMessage());
        }
    }

    /**
     * 删除指定 id 的活动与轨迹。
     *
     * <p>先删轨迹再删活动：SQLite 默认不开启外键级联，显式删除比依赖 {@code PRAGMA foreign_keys} 可靠。
     * 一并清掉本次解析出但库里没有的 id（文件改名的情况），避免主键冲突残留。
     *
     * <p>天气行只删「这次解析后消失或天气指纹变了」的那部分（{@code staleWeatherRunIds}）：
     * 仍然存在且起跑时刻 / 时长 / 坐标都没变的记录，会被原样重新插入，之前回填的天气仍然有效，
     * 没必要丢掉再消耗一次配额；而记录真的换了（同名文件内容被替换、坐标修正）时，旧天气必须作废。
     */
    private void deleteRows(Set<String> ids, Set<String> staleWeatherRunIds) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> batch = List.copyOf(ids);
        deleteWeather(staleWeatherRunIds);
        jdbc.sql("DELETE FROM track_point WHERE run_id IN (:ids)")
                .param("ids", batch)
                .update();
        jdbc.sql("DELETE FROM run_activity WHERE id IN (:ids)")
                .param("ids", batch)
                .update();
    }

    /** {@code run_weather} 没有外键，删记录必须显式带上，否则会留下孤儿行。 */
    private void deleteWeather(Set<String> runIds) {
        if (runIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM run_weather WHERE run_id IN (:ids)")
                .param("ids", List.copyOf(runIds))
                .update();
    }

    private void insertActivity(ParsedRun run, String sourceFile, String hash, String importedAt) {
        RunActivity activity = run.activity();
        jdbc.sql(INSERT_ACTIVITY)
                .param(activity.id())
                .param(activity.activityId())
                .param(activity.sport() == null ? "" : activity.sport())
                .param(RunRows.text(activity.date()))
                .param(RunRows.text(activity.startTime()))
                .param(RunRows.text(activity.endTime()))
                .param(RunRows.instantText(activity.startTimeUtc()))
                .param(activity.durationSeconds())
                .param(activity.distanceMeters())
                .param(activity.climbMeters())
                .param(activity.descentMeters())
                .param(activity.routeType())
                .param(activity.routeTypeText())
                .param(activity.trackPointCount())
                .param(activity.avgSpeedKmh())
                .param(activity.avgPaceSecondsPerKm())
                .param(activity.startLatitude())
                .param(activity.startLongitude())
                .param(activity.endLatitude())
                .param(activity.endLongitude())
                .param(activity.minAltitudeMeters())
                .param(activity.maxAltitudeMeters())
                .param(sourceFile)
                .param(activity.creator())
                .param(hash)
                .param(importedAt)
                .update();
    }

    /** 单次活动最多几千个点，逐条 INSERT 都在同一事务里，开销远小于解析 XML。 */
    private void insertTrackPoints(ParsedRun run) {
        String runId = run.activity().id();
        List<TrackPoint> points = run.trackPoints();
        for (int seq = 0; seq < points.size(); seq++) {
            TrackPoint point = points.get(seq);
            jdbc.sql(INSERT_TRACK_POINT)
                    .param(runId)
                    .param(seq)
                    .param(RunRows.text(point.timestamp()))
                    .param(point.latitude())
                    .param(point.longitude())
                    .param(point.altitudeMeters())
                    .update();
        }
    }

    /** 库里现有活动条数，用于日志与 {@code describe_data_source}。 */
    public int activityCount() {
        schema.ensure();
        return jdbc.sql("SELECT COUNT(*) FROM run_activity").query(Integer.class).single();
    }

    private void safeSaveState(SourceFile file, String hash, String errorMessage) {
        try {
            stateStore.save(file.name(), file.size(), file.modifiedMillis(), hash, true, errorMessage);
        } catch (RuntimeException e) {
            log.warn("记录来源文件状态失败 {}", file.name(), e);
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
        log.warn("跳过无法解析的 TCX 文件 {}: {}", fileName, message);
    }

    private static Set<String> names(List<SourceFile> files) {
        Set<String> names = new LinkedHashSet<>();
        for (SourceFile file : files) {
            names.add(file.name());
        }
        return names;
    }

}
