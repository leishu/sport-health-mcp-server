package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.SleepCoverage;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepPeriod;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.repository.SleepStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.service.SleepAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_sleep_summary} 的口径：两套来源怎么合并、哨兵值怎么折成 null、覆盖计数怎么算。
 *
 * <p>用真实 SQLite 而不是 mock：这里守的几条（{@code sleep_score > 0} 的过滤、
 * {@code LIKE 'PROFESSIONAL_SLEEP_%'} 的窗口聚合、{@code yyyy-MM-dd HH:mm:ss} 与
 * {@code yyyy-MM-ddTHH:mm} 两种时间格式的跨表比较）全是数据库侧行为，mock 测不出来。
 */
class SleepHealthToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private SleepHealthTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("db").resolve("sport-health.db");
        Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);

        jdbc = JdbcClient.create(dataSource);
        new SqliteSchema(jdbc, dataSource).ensure();

        seed();
        SleepStore store = new SleepStore(jdbc);
        // 固定时钟，避免「不传日期」时默认区间随运行日期漂移
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        tools = new SleepHealthTools(new SleepAnalyticsService(store, new DateRangeResolver(clock)));
    }

    /**
     * 四晚数据，覆盖三种来源组合：
     * <ul>
     *   <li>06-15：会话 + 报告（还有一条 score=0 的午睡报告要排除）；</li>
     *   <li>06-16：只有会话，评分类字段应为 null；</li>
     *   <li>06-17：只有报告（当晚的分期被午睡粘成 NAP 会话），分期要按报告窗口重算；</li>
     *   <li>06-22：只有会话，用来跨周。</li>
     * </ul>
     */
    private void seed() {
        session("2026-06-14 22:30:00", "2026-06-15 06:30:00", "2026-06-15", 480, 420, 100, 260, 60, 60);
        session("2026-06-15 23:00:00", "2026-06-16 06:00:00", "2026-06-16", 420, 400, 90, 250, 60, 20);
        session("2026-06-21 22:00:00", "2026-06-22 06:00:00", "2026-06-22", 480, 450, 110, 280, 60, 30);
        // 06-17 那天被标成 NAP（夜睡和午睡粘在一起），所以没有 NIGHT 会话
        session("2026-06-16 20:00:00", "2026-06-17 05:00:00", "2026-06-17", 540, 380, 80, 240, 40, 20,
                "NAP");

        report("2026-06-14T22:30", "2026-06-15T06:30", "2026-06-15", 80d, 92d, 10d, 50d, -1d, 100d);
        report("2026-06-15T12:30", "2026-06-15T13:00", "2026-06-15", 0d, 255d, 1092d, 0d, 0d, 255d);
        report("2026-06-16T22:00", "2026-06-17T05:00", "2026-06-17", 70d, 88d, 5d, 45d, 97d, 80d);
        // 没有会话的一天，只有一条 score=0 的午睡报告：不应该被当成一个夜晚
        report("2026-06-20T13:00", "2026-06-20T13:40", "2026-06-20", 0d, 255d, 1092d, 0d, 0d, 255d);

        // 06-15 那晚的清醒段：开头 10 分钟是入睡潜伏期（要丢掉），02:00 醒 10 分钟，
        // 03:00 醒 1 分钟（计入清醒时长但不算一次醒来）
        wake("2026-06-14T22:30", "2026-06-14T22:40");
        wake("2026-06-15T02:00", "2026-06-15T02:10");
        wake("2026-06-15T03:00", "2026-06-15T03:01");
        // 06-16 那晚只有起床前那一段，不算醒来也不算 WASO
        wake("2026-06-16T05:50", "2026-06-16T06:00");
        // 06-17 报告窗口内的分期（会话被标成 NAP，所以靠窗口重算）
        stage("PROFESSIONAL_SLEEP_DEEP", "2026-06-16T22:00", "2026-06-16T23:20");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-06-16T23:20", "2026-06-17T03:20");
        stage("PROFESSIONAL_SLEEP_DREAM", "2026-06-17T03:20", "2026-06-17T04:00");
        stage("PROFESSIONAL_SLEEP_WAKE", "2026-06-17T04:00", "2026-06-17T04:20");
    }

    @Test
    void mergesStagesAndReportIntoOneRowPerNight() {
        Map<String, Object> result = tools.getSleepSummary("2026-06-15", "2026-06-22", "day");

        assertEquals("OK", result.get("status"));
        assertEquals("2026-06-15 ~ 2026-06-22", result.get("range"));
        List<SleepNight> nights = nights(result);
        assertEquals(4, nights.size(), "score=0 的午睡报告不能自己造出一个夜晚");

        SleepNight first = nights.get(0);
        assertEquals(LocalDate.of(2026, 6, 15), first.date());
        // 时刻按项目约定输出 yyyy-MM-dd HH:mm:ss，不用 ISO 的 T
        assertEquals("2026-06-14 22:30:00", first.bedtime());
        assertEquals("2026-06-15 06:30:00", first.waketime());
        assertEquals(420, first.asleepMinutes());
        assertEquals(480, first.timeInBedMinutes());
        // 10 分钟潜伏期 + 10 分钟夜间清醒 + 1 分钟短暂觉醒，首尾两段不计入 WASO
        assertEquals(11, first.awakeMinutes());
        assertEquals(1, first.wakeCount(), "1 分钟的清醒不算一次醒来");
        assertEquals(80d, first.score(), 1e-9);
        assertEquals(92d, first.efficiencyPercent(), 1e-9);
        assertEquals(50d, first.hrvMs(), 1e-9);
        assertNull(first.spo2Percent(), "血氧 -1 是哨兵值，必须折成 null 而不是 0");
        assertEquals(10, first.sleepLatencyMinutes());
        assertTrue(first.sources().containsAll(List.of("stages", "report")), String.valueOf(first.sources()));

        SleepNight stageOnly = nights.get(1);
        assertNull(stageOnly.score(), "没有报告的夜晚评分是 null");
        assertNull(stageOnly.efficiencyPercent());
        assertNull(stageOnly.hrvMs());
        assertNull(stageOnly.sleepLatencyMinutes());
        assertEquals(List.of("stages"), stageOnly.sources());
        assertEquals(0, stageOnly.wakeCount().intValue(), "起床前那段清醒不算醒来");
        assertTrue(stageOnly.hasStages());

        SleepNight reportOnly = nights.get(2);
        assertEquals(LocalDate.of(2026, 6, 17), reportOnly.date());
        // 会话把这天标成 NAP，时长要按报告的窗口重算：80 + 240 + 40 = 360
        assertEquals(360, reportOnly.asleepMinutes());
        assertEquals(420, reportOnly.timeInBedMinutes());
        assertEquals(20, reportOnly.awakeMinutes());
        assertEquals(1, reportOnly.wakeCount());
        assertEquals(80, reportOnly.deepMinutes());
        assertEquals(240, reportOnly.lightMinutes());
        assertEquals(40, reportOnly.remMinutes());
        assertEquals(97d, reportOnly.spo2Percent(), 1e-9, "唯一一个有血氧的夜晚");
        assertEquals(70d, reportOnly.score(), 1e-9);
    }

    @Test
    void coverageExplainsWhichNightsLackAReport() {
        Map<String, Object> result = tools.getSleepSummary("2026-06-15", "2026-06-22", "day");
        SleepCoverage coverage = (SleepCoverage) result.get("coverage");

        assertEquals(4, coverage.nights());
        assertEquals(4, coverage.sessionDays());
        assertEquals(2, coverage.reportDays());
        assertEquals(2, coverage.stageOnlyNights());
        assertEquals(2, coverage.scoredNights());
        assertEquals(1, coverage.spo2Nights());
        assertEquals(50d, coverage.reportCoveragePercent(), 1e-9);
        assertNotNull(coverage.note());
        assertTrue(coverage.note().contains("2 个夜晚只有睡眠分期"), coverage.note());
        assertTrue(coverage.note().contains("1/4"), coverage.note());
    }

    @Test
    void summaryAveragesOnlyAvailableValues() {
        SleepSummary summary = (SleepSummary) tools.getSleepSummary("2026-06-15", "2026-06-22", "day")
                .get("summary");

        assertEquals(4, summary.nights());
        assertEquals(408, summary.avgAsleepMinutes(), "(420+400+360+450)/4 = 407.5 四舍五入");
        assertEquals("6h 48m", summary.avgAsleepText());
        assertEquals(450, summary.avgTimeInBedMinutes());
        assertEquals(8, summary.avgAwakeMinutes());
        assertEquals(0.5, summary.avgWakeCount(), 1e-9);
        assertEquals(7.5, summary.avgSleepLatencyMinutes(), 1e-9);
        assertEquals(75d, summary.avgScore(), 1e-9);
        assertEquals(2, summary.reportNights());
        assertEquals(90d, summary.avgEfficiencyPercent(), 1e-9);
        assertEquals(47.5, summary.avgHrvMs(), 1e-9);
        assertEquals(2, summary.hrvNights());
        assertEquals(97d, summary.avgSpo2Percent(), 1e-9);
        assertEquals(1, summary.spo2Nights());
        assertEquals(4, summary.stageNights());
        // 深睡 380 / 睡着 1630
        assertEquals(23.3, summary.avgDeepPercent(), 1e-9);
        assertEquals(13.5, summary.avgRemPercent(), 1e-9);
    }

    @Test
    void groupsByWeekAndMonth() {
        Map<String, Object> week = tools.getSleepSummary("2026-06-15", "2026-06-22", "week");
        assertEquals("week", week.get("groupBy"));
        assertFalse(week.containsKey("nights"), "周粒度不返回逐夜数组");
        List<SleepPeriod> periods = periods(week);
        assertEquals(2, periods.size());
        assertEquals("2026-W25", periods.get(0).period());
        assertEquals(LocalDate.of(2026, 6, 15), periods.get(0).from());
        assertEquals(LocalDate.of(2026, 6, 17), periods.get(0).to());
        assertEquals(3, periods.get(0).nights());
        assertEquals(2, periods.get(0).reportedNights());
        assertEquals("2026-W26", periods.get(1).period());
        assertEquals(1, periods.get(1).nights());
        assertEquals(0, periods.get(1).reportedNights());
        assertNull(periods.get(1).avgScore(), "整周都没有报告时评分为 null");

        Map<String, Object> month = tools.getSleepSummary("2026-06-01", "2026-06-30", "month");
        List<SleepPeriod> months = periods(month);
        assertEquals(1, months.size());
        assertEquals("2026-06", months.get(0).period());
        assertEquals(LocalDate.of(2026, 6, 15), months.get(0).from(), "区间从 06-01 起，但第一个有数据的夜晚是 06-15");
        assertEquals(4, months.get(0).nights());
    }

    @Test
    void rejectsInvalidArguments() {
        assertInvalid(tools.getSleepSummary(null, null, "year"), "groupBy");
        assertInvalid(tools.getSleepSummary("2026-13-01", null, null), "startDate");
        assertInvalid(tools.getSleepSummary(null, "not-a-date", null), "endDate");
        assertInvalid(tools.getSleepSummary("2026-06-22", "2026-06-15", null), "startDate");
        // 不传 groupBy 等价于 day
        assertEquals("day", tools.getSleepSummary("2026-06-15", "2026-06-16", null).get("groupBy"));
    }

    @Test
    void returnsNoDataWithCoveredRange() {
        Map<String, Object> result = tools.getSleepSummary("2020-01-01", "2020-01-31", null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-06-15 ~ 2026-06-22", result.get("coveredRange"));
        assertNotNull(result.get("message"));
    }

    /** 默认区间（两端都不传）应取睡眠数据自身的覆盖范围，而不是跑步数据的。 */
    @Test
    void defaultsToSleepCoveredRange() {
        Map<String, Object> result = tools.getSleepSummary(null, null, "month");

        assertEquals("OK", result.get("status"));
        assertEquals("2026-06-15 ~ 2026-06-22", result.get("range"));
    }

    private static void assertInvalid(Map<String, Object> result, String param) {
        assertEquals("INVALID_ARGUMENT", result.get("status"), String.valueOf(result));
        assertEquals(param, result.get("param"));
        assertNotNull(result.get("message"));
    }

    @SuppressWarnings("unchecked")
    private static List<SleepNight> nights(Map<String, Object> result) {
        return (List<SleepNight>) result.get("nights");
    }

    @SuppressWarnings("unchecked")
    private static List<SleepPeriod> periods(Map<String, Object> result) {
        return (List<SleepPeriod>) result.get("periods");
    }

    private void session(String start, String end, String day, int inBed, int asleep,
                         int deep, int light, int rem, int awake) {
        session(start, end, day, inBed, asleep, deep, light, rem, awake, "NIGHT");
    }

    private void session(String start, String end, String day, int inBed, int asleep,
                         int deep, int light, int rem, int awake, String type) {
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                       in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                       awake_minutes, noon_minutes, segment_count)
                VALUES (:start, :end, :day, :type, :inBed, :asleep, :deep, :light, :rem, :awake, 0, 1)
                """)
                .param("start", start).param("end", end).param("day", day).param("type", type)
                .param("inBed", inBed).param("asleep", asleep).param("deep", deep)
                .param("light", light).param("rem", rem).param("awake", awake)
                .update();
    }

    private void report(String start, String end, String day, Double score, Double efficiency,
                        Double latency, Double hrv, Double spo2, Double deepPart) {
        jdbc.sql("""
                INSERT INTO sleep_report (record_id, start_time, end_time, sleep_date, sleep_score,
                       sleep_efficiency, sleep_latency, avg_hrv, avg_oxygen_saturation, deep_part,
                       wake_count, turn_over_count, fall_asleep_time, rising_time)
                VALUES (:id, :start, :end, :day, :score, :eff, :latency, :hrv, :spo2, :deep,
                        0, 0, NULL, NULL)
                """)
                .param("id", start + day).param("start", start).param("end", end).param("day", day)
                .param("score", score).param("eff", efficiency).param("latency", latency)
                .param("hrv", hrv).param("spo2", spo2).param("deep", deepPart)
                .update();
    }

    private void wake(String start, String end) {
        stage("PROFESSIONAL_SLEEP_WAKE", start, end);
    }

    private void stage(String key, String start, String end) {
        jdbc.sql("""
                INSERT INTO health_metric (record_id, metric_key, start_time, end_time, type_code,
                       value_num, source_file)
                VALUES (:id, :key, :start, :end, 9, NULL, 'test.xls')
                """)
                .param("id", key + start).param("key", key).param("start", start).param("end", end)
                .update();
    }
}
