package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.ActivityMinute;
import org.example.sporthealth.domain.ActivityMinuteBlock;
import org.example.sporthealth.domain.ActivityMinuteDay;
import org.example.sporthealth.domain.ActivityMinuteType;
import org.example.sporthealth.repository.ActivityMinuteStore;
import org.example.sporthealth.repository.DailyActivityStore;
import org.example.sporthealth.repository.DerivedTables;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.service.ActivityMinuteService;
import org.example.sporthealth.service.DailyActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_activity_minutes} 的口径：一分钟一行（跨设备去重）、站立事件默认不混进来、
 * 连续段怎么拼、以及它的日合计必须与派生表 {@code daily_activity} 完全相等。
 *
 * <p>用真实 SQLite：窗口函数去重、字符串时间区间、派生表重建都是数据库侧行为，mock 测不出来。
 */
class ActivityMinuteToolsTest {

    private static final LocalDate DENSE_DAY = LocalDate.of(2026, 11, 10);

    /** 只有 3 条记录的稀疏日（模拟导出当天没过完）。 */
    private static final LocalDate SPARSE_DAY = LocalDate.of(2026, 11, 8);

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private ActivityHealthTools tools;
    private DerivedTables derived;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("db").resolve("sport-health.db");
        Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);

        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqliteSchema schema = new SqliteSchema(jdbc, dataSource);
        schema.ensure();

        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        tools = new ActivityHealthTools(new DailyActivityService(new DailyActivityStore(jdbc), clock),
                new ActivityMinuteService(new ActivityMinuteStore(jdbc), clock));
        derived = new DerivedTables(jdbc, transactions, schema);

        seed();
        derived.rebuild();
    }

    /**
     * 一个完整的一天（2026-11-10）：
     * <ul>
     *   <li>06:00、08:00 两条站立事件（整点、0 步）；</li>
     *   <li>06:02~06:04 连走 3 分钟，06:09 又走 1 分钟（中间空 4 分钟，必须断成两段）；</li>
     *   <li>07:00~07:20 跑 20 分钟（07:02 漏记，容忍 2 分钟空档所以仍是一段），
     *       其中 07:00 被另一台设备报成 sportType=2 的走路（同分钟重复）。</li>
     * </ul>
     * 一共 27 条原始记录，去重后 26 条，够 20 条的门槛，不算「稀疏日」。
     */
    private void seed() {
        minute(DENSE_DAY, "06:00", 13, 0, 0, 0.0, 0, 1, "dev-a");
        minute(DENSE_DAY, "06:02", 5, 100, 80, 4.0, 1, 0, "dev-a");
        minute(DENSE_DAY, "06:03", 5, 100, 80, 4.0, 1, 0, "dev-a");
        // 这条走路被标了 is_stand=1：它不是「站立事件」，不能算进 standEvents
        minute(DENSE_DAY, "06:04", 5, 100, 80, 4.0, 1, 1, "dev-a");
        minute(DENSE_DAY, "06:09", 2, 50, 40, 2.0, 0, 0, "dev-b");

        for (int m = 0; m <= 20; m++) {
            if (m == 2) {
                continue;
            }
            minute(DENSE_DAY, String.format("07:%02d", m), 4, 180, 170, 11.0, 1, 0, "dev-a");
        }
        // 同一分钟、另一台设备：步数更少，去重后应当被丢掉
        minute(DENSE_DAY, "07:00", 2, 170, 160, 10.0, 1, 0, "dev-b");
        minute(DENSE_DAY, "08:00", 13, 0, 0, 0.0, 0, 1, "dev-a");

        minute(SPARSE_DAY, "12:00", 5, 100, 80, 4.0, 0, 0, "dev-a");
        minute(SPARSE_DAY, "12:01", 5, 100, 80, 4.0, 0, 0, "dev-a");
        minute(SPARSE_DAY, "12:02", 5, 100, 80, 4.0, 0, 0, "dev-a");
    }

    private void minute(LocalDate date, String time, int sportType, int steps, int distance,
                        double kcal, int intensity, int stand, String device) {
        LocalDateTime start = LocalDateTime.parse(date + "T" + time);
        int recordDay = date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
        jdbc.sql("""
                        INSERT INTO activity_minute (start_time, sport_type, record_day, end_time, steps,
                                distance_meters, calories_kcal, duration_minutes, is_intensity, is_stand,
                                altitude, device_code, source_file)
                        VALUES (:startTime, :sportType, :recordDay, :endTime, :steps, :distance, :kcal,
                                1, :intensity, :stand, 0.0, :device, 'sport per minute merged data.json')
                        """)
                .param("startTime", start.format(MINUTE_FORMAT))
                .param("sportType", sportType)
                .param("recordDay", recordDay)
                .param("endTime", start.plusMinutes(1).format(MINUTE_FORMAT))
                .param("steps", steps)
                .param("distance", distance)
                .param("kcal", kcal)
                .param("intensity", intensity)
                .param("stand", stand)
                .param("device", device)
                .update();
    }

    // ---------------------------------------------------------------- 逐分钟

    @Test
    void defaultsToLatestDayAndKeepsOneRecordPerMinute() {
        Map<String, Object> result = tools.getActivityMinutes(null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("2026-11-10", result.get("date"));
        assertEquals("周二", result.get("weekday"));
        assertEquals("activity", result.get("filter"));

        // 27 条原始记录里 07:00 那条重复被合并，剩 26 条；站立 2 条不在默认列表里
        ActivityMinuteDay summary = (ActivityMinuteDay) result.get("summary");
        assertEquals(27, summary.rawRows());
        assertEquals(26, summary.records());
        assertEquals(24, summary.activityMinutes());
        assertEquals(3950, summary.steps());
        assertEquals(3.68, summary.distanceKm(), 0.001);
        assertEquals(234.0, summary.caloriesKcal(), 0.001);
        assertEquals(23, summary.activeMinutes());
        assertEquals(2, summary.standEvents());
        assertEquals("06:02", summary.firstActivity());
        assertEquals("07:21", summary.lastActivity());
        assertFalse(summary.sparse());

        List<ActivityMinute> minutes = minutes(result);
        assertEquals(24, minutes.size());
        assertTrue(minutes.stream().noneMatch(ActivityMinute::stand));
        // 同一分钟只留步数大的那条（180 步的跑步），另一台设备的 170 步被合并掉
        ActivityMinute run = minutes.stream().filter(m -> "07:00".equals(m.time())).findFirst().orElseThrow();
        assertEquals(180, run.steps());
        assertEquals("run", run.type());
        assertEquals(2, run.sourceRows());
        // 合并后的分钟也都还是 step>0 的普通记录
        assertTrue(minutes.stream().allMatch(m -> m.sourceRows() >= 1));
    }

    @Test
    void typesBucketWalkCodesTogether() {
        Map<String, Object> result = tools.getActivityMinutes("2026-11-10", "all");
        List<ActivityMinuteType> types = types(result);

        ActivityMinuteType walk = types.stream().filter(t -> "walk".equals(t.type())).findFirst().orElseThrow();
        // sportType 2 与 5 都算走路：2 是第三台设备的码，只认 5 会让换设备后的走路凭空消失
        assertEquals(List.of(2, 5), walk.sportTypes());
        assertEquals(4, walk.minutes());
        assertEquals(350, walk.steps());
        assertEquals("06:02", walk.firstTime());
        // 06:09 那条是第三台设备报的 sportType=2，同样算走路
        assertEquals("06:10", walk.lastTime());

        ActivityMinuteType run = types.stream().filter(t -> "run".equals(t.type())).findFirst().orElseThrow();
        assertEquals(20, run.minutes());
        assertEquals(3600, run.steps());

        // 站立垫底，且它「一分钟一条、0 步」
        ActivityMinuteType stand = types.get(types.size() - 1);
        assertEquals("stand", stand.type());
        assertEquals(2, stand.minutes());
        assertEquals(0, stand.steps());
    }

    // ---------------------------------------------------------------- 连续段

    @Test
    void joinsContiguousMinutesAndSplitsOnLongGap() {
        Map<String, Object> result = tools.getActivityMinutes("2026-11-10", null);
        List<ActivityMinuteBlock> blocks = blocks(result);

        assertEquals(3, result.get("blockCount"));
        assertEquals(3, blocks.size());

        ActivityMinuteBlock first = blocks.get(0);
        assertEquals("walk", first.type());
        assertEquals("06:02", first.from());
        assertEquals("06:05", first.to());
        assertEquals(3, first.minutes());
        assertEquals(300, first.steps());
        assertEquals(0.24, first.distanceKm(), 0.001);

        // 空 4 分钟（> 2 分钟容忍）必须断开，不能和前面连成一段
        ActivityMinuteBlock second = blocks.get(1);
        assertEquals("06:09", second.from());
        assertEquals("06:10", second.to());
        assertEquals(1, second.minutes());

        ActivityMinuteBlock third = blocks.get(2);
        assertEquals("run", third.type());
        assertEquals("07:00", third.from());
        // 07:02 漏记，空档 1 分钟在容忍范围内，所以整段仍然是 20 分钟
        assertEquals("07:21", third.to());
        assertEquals(20, third.minutes());
        assertEquals(3600, third.steps());
    }

    // ---------------------------------------------------------------- 过滤与校验

    @Test
    void filtersByType() {
        Map<String, Object> run = tools.getActivityMinutes("2026-11-10", "run");
        assertEquals("run", run.get("filter"));
        assertEquals(20, run.get("minuteCount"));
        assertTrue(minutes(run).stream().allMatch(m -> "run".equals(m.type())));
        // 过滤只影响明细与连续段，types / summary 仍是全天口径
        assertEquals(26, ((ActivityMinuteDay) run.get("summary")).records());
        assertEquals(3, types(run).size());

        Map<String, Object> stand = tools.getActivityMinutes("2026-11-10", "stand");
        assertEquals(2, stand.get("minuteCount"));
        assertTrue(minutes(stand).stream().allMatch(ActivityMinute::stand));
        assertEquals(0, stand.get("blockCount"));
        assertTrue(notes(stand).stream().anyMatch(n -> n.contains("整点标记")));

        Map<String, Object> all = tools.getActivityMinutes("2026-11-10", "all");
        assertEquals(26, all.get("minuteCount"));
        assertTrue(notes(all).stream().noneMatch(n -> n.contains("没算进分钟列表")));
    }

    @Test
    void rejectsUnknownTypeAndBadDate() {
        Map<String, Object> badType = tools.getActivityMinutes("2026-11-10", "swim");
        assertEquals("INVALID_ARGUMENT", badType.get("status"));
        assertEquals("type", badType.get("param"));
        assertEquals(List.of("activity", "all", "walk", "run", "stand"), badType.get("allowedValues"));

        // 宽松解析：斜杠、紧凑写法都接受（DateParser 的既有约定）
        assertEquals("OK", tools.getActivityMinutes("2026/11/10", null).get("status"));

        Map<String, Object> badDate = tools.getActivityMinutes("2026-13-01", null);
        assertEquals("INVALID_ARGUMENT", badDate.get("status"));
        assertEquals("date", badDate.get("param"));
    }

    @Test
    void reportsCoveredRangeWhenDayHasNoData() {
        Map<String, Object> result = tools.getActivityMinutes("2025-01-01", null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2025-01-01", result.get("date"));
        assertEquals("2026-11-08 ~ 2026-11-10", result.get("coveredRange"));
    }

    // ---------------------------------------------------------------- 与派生表一致

    @Test
    void flagsSparseDay() {
        Map<String, Object> result = tools.getActivityMinutes("2026-11-08", null);

        assertEquals("OK", result.get("status"));
        ActivityMinuteDay summary = (ActivityMinuteDay) result.get("summary");
        assertTrue(summary.sparse());
        assertEquals(3, summary.records());
        assertTrue(notes(result).stream().anyMatch(n -> n.contains("没戴表")));
    }

    @Test
    void dailyTotalMatchesDerivedTable() {
        Map<String, Object> minutesResult = tools.getActivityMinutes("2026-11-10", null);
        ActivityMinuteDay summary = (ActivityMinuteDay) minutesResult.get("summary");

        Map<String, Object> dailyResult = tools.getDailyActivity("2026-11-10", "2026-11-10", null);
        @SuppressWarnings("unchecked")
        List<org.example.sporthealth.domain.DailyActivityDay> days =
                (List<org.example.sporthealth.domain.DailyActivityDay>) dailyResult.get("days");
        org.example.sporthealth.domain.DailyActivityDay day = days.get(0);

        assertEquals(day.steps(), summary.steps());
        assertEquals(day.walkSteps() + day.runSteps(), summary.steps());
        assertEquals(day.distanceKm(), summary.distanceKm(), 0.001);
        assertEquals(day.caloriesKcal(), summary.caloriesKcal(), 0.001);
        assertEquals(day.activeMinutes(), summary.activeMinutes());
        assertEquals(day.standEvents(), summary.standEvents());
        // minute_rows 也要是去重后的条数，否则 sparseDays 判断会误报
        assertEquals(day.minuteRows(), summary.records());
    }

    // ---------------------------------------------------------------- 辅助

    @SuppressWarnings("unchecked")
    private static List<ActivityMinute> minutes(Map<String, Object> result) {
        List<ActivityMinute> value = (List<ActivityMinute>) result.get("minutes");
        assertNotNull(value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<ActivityMinuteBlock> blocks(Map<String, Object> result) {
        List<ActivityMinuteBlock> value = (List<ActivityMinuteBlock>) result.get("blocks");
        assertNotNull(value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<ActivityMinuteType> types(Map<String, Object> result) {
        List<ActivityMinuteType> value = (List<ActivityMinuteType>) result.get("types");
        assertNotNull(value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> result) {
        List<String> value = (List<String>) result.get("notes");
        assertNotNull(value);
        return value;
    }
}
