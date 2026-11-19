package org.example.sporthealth.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.importer.SourceFileScanner;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.importer.huawei.HealthDetailParser;
import org.example.sporthealth.importer.huawei.HuaweiExportScanner;
import org.example.sporthealth.importer.huawei.ExcelWorkbookParser;
import org.example.sporthealth.importer.huawei.MinuteActivityParser;
import org.example.sporthealth.importer.huawei.MotionPathParser;
import org.example.sporthealth.importer.huawei.SampleSequenceParser;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.weather.OpenMeteoClient;
import org.example.sporthealth.weather.WeatherBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 持久化层的核心语义：增量、幂等、变更重导、删除保留、轨迹直查。
 *
 * <p>用真实 SQLite 文件而不是 mock：增量判断依赖 {@code size/mtime/sha256} 与 UPSERT 行为，
 * 这些都是数据库层面的语义，mock 测不出来。
 */
class JdbcHealthDataRepositoryTest {

    private static final String SAMPLE = "tcx-dataset/20261025户外跑步.tcx";

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path dbFile;
    private HealthDataProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createDirectories(tempDir.resolve("tcx"));
        dbFile = tempDir.resolve("db").resolve("sport-health.db");
        copySample("20261025户外跑步.tcx");
        properties = new HealthDataProperties(dataDir.toString(), dbFile.toString(), "Asia/Shanghai", 20, 200);
    }

    @Test
    void importsAllFilesOnFirstLoad() {
        RunDataset dataset = repository().dataset();

        assertEquals(1, dataset.runCount());
        assertEquals(List.of("20261025户外跑步.tcx"), dataset.sourceFiles());
        assertTrue(dataset.warnings().isEmpty(), dataset.warnings().toString());

        RunActivity activity = dataset.activities().get(0);
        assertEquals("20261025户外跑步", activity.id());
        assertEquals(7123d, activity.distanceMeters(), 0.5);
        assertEquals(1216, activity.trackPointCount());
    }

    /** 原始流直查：按类型过滤，按 seq 升序，data 里的键值对要被解出来。 */
    @Test
    void readsTrackStreamsByType() {
        JdbcHealthDataRepository repository = repository();
        String runId = repository.dataset().activities().get(0).id();
        for (int seq = 0; seq < 2; seq++) {
            double endMeters = (seq + 1) * 1000d;
            jdbc().sql("""
                            INSERT INTO track_stream (run_id, stream_type, seq, time_millis, point_index, value, data)
                            VALUES (?, 'pm2', ?, NULL, ?, ?, ?)
                            """)
                    .param(runId)
                    .param(seq)
                    .param(seq + 1)
                    .param(300d + seq)
                    .param("{\"tp\":\"pm2\",\"d\":\"" + (long) endMeters + "\",\"n\":\"" + (seq + 1)
                            + "\",\"p\":\"" + (300d + seq) + "\"}")
                    .update();
        }
        jdbc().sql("""
                        INSERT INTO track_stream (run_id, stream_type, seq, time_millis, point_index, value, data)
                        VALUES (?, 'rs', 0, NULL, 5, 3.2, '{"tp":"rs","v":"3.2","k":"5"}')
                        """)
                .param(runId)
                .update();

        List<TrackStream> pm2 = repository.streams(runId, "pm2");

        assertEquals(2, pm2.size());
        assertEquals(List.of(0, 1), pm2.stream().map(TrackStream::seq).toList());
        assertEquals("1000", pm2.get(0).fields().get("d"), "data 里的 d 要解析出来");
        assertEquals(300d, pm2.get(0).value(), 1e-9);
        assertEquals(3, repository.streams(runId, null).size(), "不传类型时返回全部流（2 行 pm2 + 1 行 rs）");
        assertTrue(repository.streams("no-such-run", null).isEmpty());
    }

    @Test
    void workoutCountCoversNonRunningActivities() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        // 一次步行：不进跑步快照（sport_family != RUN），但必须算进「全部运动」
        jdbc().sql("""
                        INSERT INTO run_activity (id, activity_id, sport, sport_family, run_date, start_time,
                            end_time, start_time_utc, duration_seconds, distance_meters, track_point_count,
                            source_file, content_hash, imported_at, source_kind)
                        VALUES ('walk-1', 'walk-1', 'WALKING', 'WALK', '2026-11-01', '2026-11-01T07:00:00',
                            '2026-11-01T07:20:00', '2026-10-31T23:00:00Z', 1200, 1500, 0,
                            'walk.json', 'hash-walk', '2026-11-01T08:00:00Z', 'huawei')
                        """)
                .update();

        assertEquals(1, repository.dataset().runCount(), "跑步口径只含 sport_family='RUN'");
        assertEquals(2, repository.workoutCount(), "全部运动口径要把步行算进来");
    }

    @Test
    void secondSyncSkipsUnchangedFilesAndStaysIdempotent() {
        JdbcHealthDataRepository first = repository();
        first.dataset();
        assertEquals(1, first.lastSync().imported());

        JdbcHealthDataRepository second = repository();
        second.reload();

        assertEquals(0, second.lastSync().imported());
        assertEquals(1, second.lastSync().unchanged());
        assertEquals(1, second.dataset().runCount());
        assertEquals(1216, count("track_point"));
    }

    @Test
    void metadataChangedButContentSameIsNotReimported() {
        repository().dataset();

        // 直接把库里的 mtime 改掉：等价于「文件被 touch 过」。不调 setLastModifiedTime，
        // 因为沙箱的 /tmp 是只读时间戳的 tmpfs，固定值或偏移都会抛 FileSystemException。
        jdbc().sql("UPDATE source_file SET modified_millis = modified_millis - 1000").update();

        JdbcHealthDataRepository repository = repository();
        repository.reload();

        assertEquals(0, repository.lastSync().imported(), "内容没变，只更新元数据");
        assertEquals(1, repository.lastSync().unchanged());
        assertEquals(1216, count("track_point"));
        assertEquals(1, count("run_activity"));
    }

    @Test
    void changedFileReplacesOldRowsInsteadOfDuplicating() throws Exception {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        String originalHash = queryString("SELECT content_hash FROM run_activity");

        // 改内容：去掉整段 Track，距离也改小，但仍是一个合法 TCX
        Path file = dataDir.resolve("20261025户外跑步.tcx");
        String xml = Files.readString(file);
        String trimmed = xml.replaceAll("(?s)<Track>.*?</Track>", "")
                .replaceAll("<DistanceMeters>[^<]*</DistanceMeters>", "<DistanceMeters>1000.0</DistanceMeters>");
        writeAndTouch(file, trimmed);

        repository.reload();

        assertEquals(1, repository.lastSync().imported());
        assertEquals(1, repository.dataset().runCount(), "同一 id 不能变成两行");
        assertEquals(1000d, repository.dataset().activities().get(0).distanceMeters(), 0.5);
        assertEquals(0, count("track_point"), "旧轨迹随记录一起替换");
        assertNotEquals(originalHash, queryString("SELECT content_hash FROM run_activity"));
    }

    @Test
    void deletedSourceFileKeepsHistoricalRuns() throws IOException {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        Files.delete(dataDir.resolve("20261025户外跑步.tcx"));
        repository.reload();

        RunDataset dataset = repository.dataset();
        assertEquals(1, dataset.runCount(), "源文件删除后历史记录仍然可用");
        assertEquals(List.of("20261025户外跑步.tcx"), dataset.sourceFiles());
        assertEquals(List.of("20261025户外跑步.tcx"), repository.lastSync().missingSourceFiles());
        assertEquals(1216, repository.route("20261025户外跑步").size(), "轨迹也已经入库");
    }

    @Test
    void reimportKeepsWeatherOfRecordsThatStillExist() throws Exception {
        Path file = dataDir.resolve("20261025户外跑步.tcx");
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertWeatherRow("20261025户外跑步");

        // 内容变了（距离改小）但记录 id 不变：记录还是同一次跑步，天气不必重拉
        String xml = Files.readString(file)
                .replaceAll("<DistanceMeters>[^<]*</DistanceMeters>", "<DistanceMeters>1000.0</DistanceMeters>");
        writeAndTouch(file, xml);
        repository.reload();

        assertEquals(1, repository.lastSync().imported());
        assertEquals(1, count("run_weather"), "记录仍在就不该丢掉已回填的天气");
        assertEquals("20261025户外跑步", queryString("SELECT run_id FROM run_weather"));
    }

    @Test
    void reimportDropsWeatherWhenRunTimeChanges() throws Exception {
        Path file = dataDir.resolve("20261025户外跑步.tcx");
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        insertWeatherRow("20261025户外跑步");

        // 同名文件内容被换成另一次跑步：id 不变，但起跑时刻变了，旧天气不能再算到新记录头上
        String xml = Files.readString(file)
                .replace("2026-10-24T22:56:16.433Z", "2026-10-24T23:56:16.433Z");
        writeAndTouch(file, xml);
        repository.reload();

        assertEquals(1, repository.lastSync().imported());
        assertEquals(0, count("run_weather"), "起跑时刻变了，旧天气必须作废");
    }

    @Test
    void vanishedActivityTakesItsWeatherRowWithIt() throws Exception {
        Path file = dataDir.resolve("双记录.tcx");
        Files.writeString(file, twoActivityXml(true));
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        // setUp 里已经有一个单 Activity 的样本文件，这里再加两个
        assertEquals(3, count("run_activity"));
        insertWeatherRow("双记录#1");
        insertWeatherRow("双记录#2");

        // 文件里删掉一个 Activity：两个 #n 形式的 id 都不再存在，只剩不带序号的「双记录」
        writeAndTouch(file, twoActivityXml(false));
        repository.reload();

        assertEquals(2, count("run_activity"));
        assertEquals(0, count("run_weather"), "消失的 Activity 不能留下孤儿天气行");
    }

    @Test
    void orphanWeatherRowsAreCleanedOnSync() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        // 模拟历史遗留：某条记录的天气行还在，记录本身已经被重导删除（回填关闭时没有任何真实天气行）
        insertWeatherRow("已删除的记录");
        assertEquals(1, count("run_weather"));

        repository.reload();

        assertEquals(0, count("run_weather"), "回填关闭时也要清理孤儿行");
    }

    @Test
    void statsIgnoreWeatherRowsOfDeletedRuns() {
        repository().dataset();
        insertWeatherRow("20261025户外跑步");
        insertWeatherRow("ghost");
        jdbc().sql("UPDATE run_weather SET fetched_at = '2026-11-05T00:00:00Z' WHERE run_id = 'ghost'")
                .update();

        RunWeatherStore.Stats stats = new RunWeatherStore(jdbc()).stats();

        assertEquals(1, stats.totalRuns());
        assertEquals(1, stats.ok(), "孤儿行不能算进覆盖率，否则能算出超过 100%");
        assertEquals(0, stats.missing());
        assertEquals("2026-11-01T00:00:00Z", stats.lastFetchedAt(), "孤儿行的抓取时间也不能混进统计");
    }

    @Test
    void routeReadsPersistedTrackWithoutSourceFile() throws IOException {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        Files.delete(dataDir.resolve("20261025户外跑步.tcx"));

        List<TrackPoint> points = repository().route("20261025户外跑步");

        assertEquals(1216, points.size());
        TrackPoint first = points.get(0);
        assertNotNull(first.timestamp());
        assertNotNull(first.latitude());
        assertNotNull(first.longitude());
        assertEquals(45.9d, first.altitudeMeters(), 0.01);
    }

    @Test
    void unknownRunIdReturnsEmptyRoute() {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        assertTrue(repository.route("不存在的记录").isEmpty());
        assertTrue(repository.route(null).isEmpty());
    }

    @Test
    void missingPositionStaysNullAfterRoundTrip() throws IOException {
        Path file = dataDir.resolve("20261110缺坐标.tcx");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-11-10T01:00:00.000Z</Id>
                      <Lap StartTime="2026-11-10T01:00:00.000Z">
                        <TotalTimeSeconds>120.0</TotalTimeSeconds>
                        <DistanceMeters>400.0</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-11-10T01:00:00.000Z</Time><AltitudeMeters>50.0</AltitudeMeters></Trackpoint>
                          <Trackpoint>
                            <Time>2026-11-10T01:00:30.000Z</Time>
                            <Position><LatitudeDegrees>39.9</LatitudeDegrees><LongitudeDegrees>116.4</LongitudeDegrees></Position>
                          </Trackpoint>
                        </Track>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """);

        JdbcHealthDataRepository repository = repository();
        repository.dataset();
        List<TrackPoint> points = repository.route("20261110缺坐标");

        assertEquals(2, points.size());
        assertNull(points.get(0).latitude(), "缺坐标写库再读出必须仍是 null，而不是 0");
        assertNull(points.get(0).longitude());
        assertEquals(50.0d, points.get(0).altitudeMeters(), 0.01);
        assertNull(points.get(1).altitudeMeters());
        assertEquals(39.9d, points.get(1).latitude(), 0.0001);
    }

    @Test
    void brokenFileDoesNotDestroyExistingData() throws Exception {
        JdbcHealthDataRepository repository = repository();
        repository.dataset();

        Path file = dataDir.resolve("20261025户外跑步.tcx");
        writeAndTouch(file, "<不是TCX/>");

        repository.reload();

        assertFalse(repository.lastSync().warnings().isEmpty());
        assertEquals(1, repository.dataset().runCount(), "解析失败时不删除旧数据");
        assertEquals(1216, repository.route("20261025户外跑步").size());
    }

    @Test
    void metadataIsPersistedForFastPath() {
        repository().dataset();

        Map<String, Object> state = jdbc().sql("SELECT size, modified_millis, content_hash, status FROM source_file")
                .query()
                .singleRow();
        assertTrue(((Number) state.get("size")).longValue() > 0);
        assertTrue(((Number) state.get("modified_millis")).longValue() > 0);
        assertEquals(64, ((String) state.get("content_hash")).length());
        assertEquals("OK", state.get("status"));
    }

    /**
     * 回归用例：连接池上限为 1 时，建表不能包在事务里。
     *
     * <p>曾经 {@link SqliteSchema} 在事务内再向 DataSource 要连接，池里唯一的连接被事务占住，
     * 迁移会一直等到 {@code connectionTimeout} 才失败；仓储是懒加载，所以启动日志正常，
     * 问题只在第一次工具调用时暴露。
     */
    @Test
    void migratesSchemaWhenThePoolHasASingleConnection() {
        SQLiteDataSource plain = dataSource();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("single-connection");
        hikari.setDataSource(plain);
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(5_000);

        try (HikariDataSource pooled = new HikariDataSource(hikari)) {
            JdbcClient jdbc = JdbcClient.create(pooled);
            SqliteSchema schema = new SqliteSchema(jdbc, pooled);

            assertDoesNotThrow(schema::ensure);
            assertEquals(SqliteSchema.SCHEMA_VERSION,
                    jdbc.sql("PRAGMA user_version").query(Integer.class).single());
            assertEquals(1, jdbc.sql(
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'track_point'")
                    .query(Integer.class).single());
        }
    }

    /** 解析失败的文件内容不变时不再重试，但失败原因必须持续出现在 warnings 里。 */
    @Test
    void keepsReportingParseFailureWhileTheFileIsUnchanged() throws Exception {
        repository().dataset();

        Path file = dataDir.resolve("20261025户外跑步.tcx");
        writeAndTouch(file, "<不是TCX/>");

        JdbcHealthDataRepository broken = repository();
        broken.reload();
        assertFalse(broken.lastSync().warnings().isEmpty());

        JdbcHealthDataRepository second = repository();
        second.reload();

        assertEquals(0, second.lastSync().imported());
        assertEquals(1, second.lastSync().unchanged());
        assertTrue(second.lastSync().warnings().stream()
                        .anyMatch(warning -> warning.contains("20261025户外跑步.tcx")),
                "文件没变也要继续报告上次的解析失败: " + second.lastSync().warnings());
    }

    /** 目录为空（路径配错、挂载点未就绪）不能清空指纹表，否则恢复后全量重导。 */
    @Test
    void emptyDataDirKeepsFingerprints() throws IOException {
        repository().dataset();
        assertEquals(1, count("source_file"));

        Files.delete(dataDir.resolve("20261025户外跑步.tcx"));
        JdbcHealthDataRepository repository = repository();
        repository.reload();

        assertEquals(1, count("source_file"), "目录为空时不应清空指纹表");
        assertEquals(0, repository.lastSync().imported());
        assertEquals(1, repository.dataset().runCount(), "历史记录仍然可用");
    }

    // ------------------------------------------------------------------

    /** 已有版本 1 的库要能原地升级，不能要求用户删库重建。 */
    @Test
    void upgradesVersionOneDatabaseInPlace() {
        JdbcClient jdbc = jdbc();
        jdbc.sql("""
                CREATE TABLE source_file (
                    source_file TEXT PRIMARY KEY,
                    size INTEGER NOT NULL,
                    modified_millis INTEGER NOT NULL,
                    content_hash TEXT,
                    synced_at TEXT NOT NULL)
                """).update();
        jdbc.sql("""
                CREATE TABLE run_activity (
                    id TEXT PRIMARY KEY, activity_id TEXT NOT NULL, sport TEXT NOT NULL,
                    run_date TEXT NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL,
                    start_time_utc TEXT NOT NULL, duration_seconds REAL NOT NULL,
                    distance_meters REAL NOT NULL, climb_meters REAL, descent_meters REAL,
                    route_type TEXT, route_type_text TEXT, track_point_count INTEGER NOT NULL,
                    avg_speed_kmh REAL, avg_pace_seconds REAL, start_latitude REAL,
                    start_longitude REAL, end_latitude REAL, end_longitude REAL,
                    min_altitude_meters REAL, max_altitude_meters REAL, source_file TEXT NOT NULL,
                    creator TEXT, content_hash TEXT NOT NULL, imported_at TEXT NOT NULL)
                """).update();
        jdbc.sql("""
                CREATE TABLE track_point (
                    run_id TEXT NOT NULL, seq INTEGER NOT NULL, timestamp TEXT NOT NULL,
                    latitude REAL, longitude REAL, altitude REAL,
                    PRIMARY KEY (run_id, seq)) WITHOUT ROWID
                """).update();
        jdbc.sql("PRAGMA user_version = 1").update();
        jdbc.sql("""
                INSERT INTO source_file (source_file, size, modified_millis, content_hash, synced_at)
                VALUES ('old.tcx', 10, 1000, NULL, '2026-01-01T00:00:00Z')
                """).update();

        new SqliteSchema(jdbc, dataSource()).ensure();

        assertEquals(SqliteSchema.SCHEMA_VERSION, jdbc.sql("PRAGMA user_version").query(Integer.class).single());
        Map<String, Object> row = jdbc.sql("SELECT status, error_message FROM source_file")
                .query()
                .singleRow();
        assertEquals("OK", row.get("status"), "老行按默认值视为同步成功");
        assertNull(row.get("error_message"));
    }

    /** 直接种一条天气行：只验证「记录没了，天气行要不要跟着走」，不验证回填。 */
    private void insertWeatherRow(String runId) {
        jdbc().sql("""
                INSERT INTO run_weather (run_id, provider, fetched_at, fetched_epoch_millis, status)
                VALUES (?, 'open-meteo', '2026-11-01T00:00:00Z', 1761955200000, 'OK')
                """)
                .param(runId)
                .update();
    }

    /** 两个 Activity 的合法 TCX；{@code withSecond=false} 时只留第一个，用于模拟记录消失。 */
    private static String twoActivityXml(boolean withSecond) {
        String second = withSecond ? """
                    <Activity Sport="户外跑步">
                      <Id>2026-11-02T00:00:00Z</Id>
                      <Lap StartTime="2026-11-02T00:00:00Z">
                        <TotalTimeSeconds>120</TotalTimeSeconds><DistanceMeters>500</DistanceMeters>
                      </Lap>
                    </Activity>
                """ : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-11-01T00:00:00Z</Id>
                      <Lap StartTime="2026-11-01T00:00:00Z">
                        <TotalTimeSeconds>60</TotalTimeSeconds><DistanceMeters>100</DistanceMeters>
                      </Lap>
                    </Activity>
                """ + second + """
                  </Activities>
                </TrainingCenterDatabase>
                """;
    }

    private JdbcHealthDataRepository repository() {
        SQLiteDataSource dataSource = dataSource();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqliteSchema schema = new SqliteSchema(jdbc, dataSource);
        SourceFileStateStore stateStore = new SourceFileStateStore(jdbc, schema);
        DerivedTables derived = new DerivedTables(jdbc, transactions, schema);
        HuaweiExportImporter huawei = new HuaweiExportImporter(dataSource, jdbc, transactions,
                new HuaweiExportScanner(), new MotionPathParser(), new HealthDetailParser(),
                new MinuteActivityParser(), new SampleSequenceParser(),
                new ExcelWorkbookParser(), properties, stateStore, schema, derived);
        TcxIncrementalImporter importer = new TcxIncrementalImporter(
                jdbc, transactions, new SourceFileScanner(), new TcxParser(properties),
                properties, stateStore, schema, offlineBackfill(jdbc), huawei);
        return new JdbcHealthDataRepository(jdbc, importer, properties, schema);
    }

    /**
     * 天气回填关闭的实例。
     *
     * <p>本测试聚焦导入语义，不能真的去访问 open-meteo：既慢又不稳定，还会消耗配额。
     * {@code enabled=false} 时 {@code WeatherBackfill} 直接返回，完全不碰网络。
     */
    private WeatherBackfill offlineBackfill(JdbcClient jdbc) {
        WeatherProperties weather = new WeatherProperties(false, null, null, 0, 0, null, 7, 50);
        OpenMeteoClient client = new OpenMeteoClient(
                org.springframework.web.reactive.function.client.WebClient.create(), weather);
        return new WeatherBackfill(new RunWeatherStore(jdbc), client, weather);
    }

    /** 每次新建连接：SQLite 文件是外部状态，连接池在这里没有意义。 */
    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    private SQLiteDataSource dataSource() {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

    private int count(String table) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbc().sql(sql).query(String.class).single();
    }

    private void copySample(String name) throws IOException {
        try (var input = new ClassPathResource(SAMPLE).getInputStream()) {
            Files.copy(input, dataDir.resolve(name));
        }
    }

    /**
     * 重写文件内容，得到一个 mtime 不同的版本。
     *
     * <p>不显式设置时间戳：沙箱的 /tmp 是只读时间戳的 tmpfs。为了让 mtime 一定变化，
     * 先睡到一个与原来不同的一秒再去写——增量同步的 mtime 精度就是秒。
     */
    private static void writeAndTouch(Path file, String content) throws IOException, InterruptedException {
        long before = Files.getLastModifiedTime(file).toMillis() / 1000L;
        while (Files.getLastModifiedTime(file).toMillis() / 1000L == before) {
            Thread.sleep(20);
            Files.writeString(file, content);
        }
    }
}
