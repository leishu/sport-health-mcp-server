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

import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.HeartRateStat;
import org.example.sporthealth.domain.VitalsAvailability;
import org.example.sporthealth.domain.VitalsCoverage;
import org.example.sporthealth.domain.VitalsMetricStat;
import org.example.sporthealth.domain.VitalsPeriod;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.VitalsStore;
import org.example.sporthealth.service.VitalsAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_daily_vitals} 的口径：四类指标各自的分母、缺失与稀疏怎么表达、趋势窗口怎么取。
 *
 * <p>用真实 SQLite 而不是 mock：这里守的几条（{@code heart_rate_samples > 0} 判定「有没有心率」、
 * 按指标分别 COUNT、字符串日期范围的边界）都是数据库侧行为，mock 测不出来。
 */
class VitalsHealthToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private VitalsHealthTools tools;

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
        // 固定时钟比数据晚了 9 个月：默认区间必须取「数据最新一天」，不是「今天」
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        tools = new VitalsHealthTools(new VitalsAnalyticsService(new VitalsStore(jdbc), clock));
    }

    /**
     * 40 天（2026-01-01 ~ 2026-02-09），刻意让四类指标的起点错开：
     * <ul>
     *   <li>静息心率与全天心率：40 天全有，静息心率每 5 天降 1 bpm（48 → 41）；</li>
     *   <li>HRV 与压力：从 01-11 起（i≥10），HRV 从 60.0 每 5 天降 1.0；</li>
     *   <li>VO2max：只有 01-01 / 01-21 / 02-09 三天，中间断档，用来验证稀疏判定。</li>
     * </ul>
     * 这样「默认 30 天区间」正好落在 HRV 已有数据的那一段，趋势测试才有首窗口可用。
     */
    private void seed() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 40; i++) {
            LocalDate date = start.plusDays(i);
            boolean late = i >= 10;
            jdbc.sql("""
                            INSERT INTO daily_vitals (heart_date, resting_heart_rate, hrv_rmssd,
                                avg_heart_rate, min_heart_rate, max_heart_rate, heart_rate_samples,
                                stress_avg, stress_max, vo2max)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(date.toString(),
                            48 - i / 5,
                            late ? 60.0 - (i - 10) * 0.2 : null,
                            58.0 + (i % 5) * 0.5,
                            38,
                            120,
                            300,
                            late ? 25.0 : null,
                            late ? 45 : null,
                            i == 0 || i == 20 || i == 39 ? 50.0 : null)
                    .update();
        }
    }

    @Test
    void defaultsToLastThirtyDaysOfDataNotToday() {
        Map<String, Object> result = tools.getDailyVitals(null, null, null);

        assertEquals("OK", result.get("status"));
        // 时钟是 11-20，但数据只到 02-09：默认区间必须贴着数据末端，否则尾巴全是空白
        assertEquals("2026-01-11 ~ 2026-02-09", result.get("range"));
        assertEquals("day", result.get("groupBy"));
        assertEquals(30, days(result).size());
    }

    @Test
    void coverageCountsEachMetricOnItsOwnDenominator() {
        Map<String, Object> result = tools.getDailyVitals("2026-01-01", "2026-02-09", "day");
        VitalsCoverage coverage = (VitalsCoverage) result.get("coverage");

        assertEquals(40, coverage.days());
        assertEquals(40, coverage.dailyRows());
        assertEquals(40, coverage.restingHeartRateDays());
        assertEquals(30, coverage.hrvDays(), "HRV 只有 01-11 起的 30 天");
        assertEquals(30, coverage.stressDays());
        assertEquals(3, coverage.vo2maxDays());
        assertEquals(40, coverage.heartRateDays());
    }

    @Test
    void metricsOmitMetricThatHasNoValueInRange() {
        // 前 10 天还没有 HRV 与压力：这两个键不该出现，而不是给一个全 null 的对象
        Map<String, Object> result = tools.getDailyVitals("2026-01-01", "2026-01-10", "day");
        Map<String, Object> metrics = metrics(result);

        assertTrue(metrics.containsKey("restingHeartRate"));
        assertTrue(metrics.containsKey("vo2max"));
        assertFalse(metrics.containsKey("hrv"));
        assertFalse(metrics.containsKey("stress"));

        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("没有HRV")), String.valueOf(notes));
        assertTrue(notes.stream().anyMatch(n -> n.contains("2026-01-11")), "要说明 HRV 从哪天才有");
    }

    @Test
    void metricStatAveragesOnlyAvailableValues() {
        Map<String, Object> result = tools.getDailyVitals("2026-01-11", "2026-01-15", "day");
        Map<String, Object> metrics = metrics(result);

        // 01-11 起 i=10..14：HRV 60.0 / 59.8 / 59.6 / 59.4 / 59.2
        VitalsMetricStat hrv = (VitalsMetricStat) metrics.get("hrv");
        assertEquals("ms", hrv.unit());
        assertTrue(hrv.higherIsBetter());
        assertEquals(5, hrv.count());
        assertEquals(59.6, hrv.average(), 1e-9);
        assertEquals(59.2, hrv.latest(), 1e-9);
        assertEquals(LocalDate.of(2026, 1, 15), hrv.latestDate());
        // 只有 5 天，不到 7 天，不做首末对比
        assertNull(hrv.change());
        assertEquals("unknown", hrv.direction());
    }

    @Test
    void trendUsesFirstAndLastSevenDayWindows() {
        Map<String, Object> result = tools.getDailyVitals("2026-01-11", "2026-02-09", "day");
        Map<String, Object> metrics = metrics(result);

        VitalsMetricStat rhr = (VitalsMetricStat) metrics.get("restingHeartRate");
        assertEquals("bpm", rhr.unit());
        assertFalse(rhr.higherIsBetter());
        assertEquals(30, rhr.count());
        // 28 天基线窗口 01-13~02-09：(46×3 + 45×5 + 44×5 + 43×5 + 42×5 + 41×5) / 28 = 43.3，
        // 静息心率按整 bpm 输出，所以是 43
        assertEquals(43.0, rhr.baseline(), 1e-9);
        // 首 7 天（01-11~01-17）均值 45.7，末 7 天（02-03~02-09）均值 41.3
        assertEquals(-4.4, rhr.change(), 0.05);
        assertEquals("down", rhr.direction());
        assertEquals("improving", rhr.assessment(), "静息心率下降是改善，不能只看 up/down");

        // HRV 同期在降：数值 down，价值 worsening —— 与静息心率正好相反
        VitalsMetricStat hrv = (VitalsMetricStat) metrics.get("hrv");
        assertEquals("down", hrv.direction());
        assertEquals("worsening", hrv.assessment());
    }

    @Test
    void sparseVo2maxGetsAGapWarning() {
        Map<String, Object> result = tools.getDailyVitals("2026-01-01", "2026-02-09", "day");

        List<VitalsAvailability> availability = availability(result);
        VitalsAvailability vo2 = availability.stream()
                .filter(a -> "vo2max".equals(a.metric())).findFirst().orElseThrow();
        assertEquals(3, vo2.days());
        assertEquals(40, vo2.spanDays());

        assertTrue(notes(result).stream().anyMatch(n -> n.contains("VO2max 在全库里只有 3 天有值")),
                String.valueOf(notes(result)));
    }

    @Test
    void weeklyPeriodsMarkTruncatedEdge() {
        // 01-07 是周三，01-20 是周二：首末两个周期都会被区间截断
        Map<String, Object> result = tools.getDailyVitals("2026-01-07", "2026-01-20", "week");
        List<VitalsPeriod> periods = periods(result);

        assertEquals(3, periods.size());
        assertEquals("2026-W02", periods.get(0).period());
        assertEquals(LocalDate.of(2026, 1, 5), periods.get(0).from(), "周从周一算起");
        assertFalse(periods.get(0).complete(), "01-05~01-06 不在查询区间内");

        assertEquals("2026-W03", periods.get(1).period());
        assertTrue(periods.get(1).complete());
        assertEquals(7, periods.get(1).days());

        assertEquals("2026-W04", periods.get(2).period());
        assertFalse(periods.get(2).complete(), "01-21 之后不在查询区间内");
    }

    @Test
    void heartRateKeepsSampledExtremes() {
        Map<String, Object> result = tools.getDailyVitals("2026-01-01", "2026-02-09", "day");
        HeartRateStat heartRate = (HeartRateStat) result.get("heartRate");

        assertEquals(40, heartRate.days());
        // 各天均值 58.0~60.0 再平均，不等于极值
        assertTrue(heartRate.average() >= 58 && heartRate.average() <= 60, String.valueOf(heartRate.average()));
        assertEquals(38, heartRate.lowest().intValue(), "全天采到的最低心率");
        assertEquals(120, heartRate.highest().intValue());
    }

    @Test
    void rejectsInvalidArguments() {
        Map<String, Object> badGroup = tools.getDailyVitals(null, null, "hour");
        assertEquals("INVALID_ARGUMENT", badGroup.get("status"));
        assertEquals("groupBy", badGroup.get("param"));
        assertEquals(List.of("day", "week", "month"), badGroup.get("allowedValues"));

        Map<String, Object> badDate = tools.getDailyVitals("2026-13-01", null, null);
        assertEquals("INVALID_ARGUMENT", badDate.get("status"));
        assertEquals("startDate", badDate.get("param"));

        Map<String, Object> reversed = tools.getDailyVitals("2026-02-09", "2026-01-01", null);
        assertEquals("INVALID_ARGUMENT", reversed.get("status"));

        Map<String, Object> tooLong = tools.getDailyVitals("2026-01-01", "2026-02-09", "day");
        assertEquals("OK", tooLong.get("status"), "40 天在 92 天上限之内");

        Map<String, Object> overLimit = tools.getDailyVitals("2025-01-01", "2026-02-09", "day");
        assertEquals("INVALID_ARGUMENT", overLimit.get("status"));
        assertTrue(String.valueOf(overLimit.get("message")).contains("groupBy=week"));

        Map<String, Object> empty = tools.getDailyVitals("2025-01-01", "2025-01-31", "day");
        assertEquals("NO_DATA", empty.get("status"));
        assertNotNull(empty.get("coveredRange"));
    }

    // ---------------------------------------------------------------- 取返回体的小工具

    @SuppressWarnings("unchecked")
    private static List<DailyVitals> days(Map<String, Object> result) {
        return (List<DailyVitals>) result.get("days");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metrics(Map<String, Object> result) {
        return (Map<String, Object>) result.get("metrics");
    }

    @SuppressWarnings("unchecked")
    private static List<VitalsAvailability> availability(Map<String, Object> result) {
        return (List<VitalsAvailability>) result.get("availability");
    }

    @SuppressWarnings("unchecked")
    private static List<VitalsPeriod> periods(Map<String, Object> result) {
        return (List<VitalsPeriod>) result.get("periods");
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> result) {
        return (List<String>) result.get("notes");
    }
}
