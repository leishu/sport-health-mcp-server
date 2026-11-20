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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.ActivityCoverage;
import org.example.sporthealth.domain.ActivityGoals;
import org.example.sporthealth.domain.ActivityPeriod;
import org.example.sporthealth.domain.ActivitySummary;
import org.example.sporthealth.domain.ActivityTrend;
import org.example.sporthealth.domain.DailyActivityDay;
import org.example.sporthealth.repository.ActivityMinuteStore;
import org.example.sporthealth.repository.DailyActivityStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.service.ActivityMinuteService;
import org.example.sporthealth.service.DailyActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_daily_activity} 的口径：日均只按有记录的天算、达标天数怎么数、趋势窗口怎么取、
 * 站立为什么是「次数」。
 *
 * <p>用真实 SQLite 而不是 mock：这里守的几条（字符串日期范围、缺天不补 0、{@code stand_events}
 * 是计数列、周周期被区间截断）都是数据库与日期边界行为，mock 测不出来。
 */
class DailyActivityToolsTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /** 40 天的种子数据里刻意留空的那一天。 */
    private static final int MISSING_INDEX = 19;

    /** 步数特别少的那一天（久坐日）。 */
    private static final int LOW_INDEX = 5;

    /** 分钟数据异常少的那一天（可能没戴表 / 导出没过完）。 */
    private static final int SPARSE_INDEX = 25;

    private static final int SEED_DAYS = 40;

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private ActivityHealthTools tools;

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
        tools = new ActivityHealthTools(new DailyActivityService(new DailyActivityStore(jdbc), clock),
                new ActivityMinuteService(new ActivityMinuteStore(jdbc), clock));
    }

    /**
     * 40 天（2026-01-01 ~ 2026-02-09）：
     * <ul>
     *   <li>步数每天 +100（6000 起），只有 01-06 是 3000 步的久坐日；</li>
     *   <li>中高强度隔天 30 分钟，站立每三天 12 次，用来数达标天数；</li>
     *   <li>01-20 整天没有记录（测 missingDays），01-26 只有 6 条分钟数据（测 sparseDays）。</li>
     * </ul>
     */
    private void seed() {
        for (int i = 0; i < SEED_DAYS; i++) {
            if (i == MISSING_INDEX) {
                continue;
            }
            LocalDate date = START.plusDays(i);
            int steps = stepsAt(i);
            insert(date,
                    steps,
                    Math.max(0, steps - 500),
                    500,
                    steps * 0.8,
                    steps * 0.04,
                    i % 2 == 0 ? 30 : 10,
                    i % 3 == 0 ? 12 : 8,
                    i == SPARSE_INDEX ? 6 : 200);
        }
    }

    private static int stepsAt(int i) {
        return i == LOW_INDEX ? 3000 : 6000 + i * 100;
    }

    private void insert(LocalDate date, int steps, int walkSteps, int runSteps, double distanceMeters,
                        double calories, int activeMinutes, int standEvents, int minuteRows) {
        jdbc.sql("""
                        INSERT INTO daily_activity (activity_date, steps, walk_steps, run_steps,
                            distance_meters, calories_kcal, active_minutes, stand_events, minute_rows)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(date.toString(), steps, walkSteps, runSteps, distanceMeters, calories,
                        activeMinutes, standEvents, minuteRows)
                .update();
    }

    @Test
    void defaultsToLatestThirtyDaysAndCountsOnlyRecordedDays() {
        Map<String, Object> result = tools.getDailyActivity(null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("day", result.get("groupBy"));
        // 数据最新一天是 2026-02-09，往前 30 天即 01-11 ~ 02-09
        assertEquals("2026-01-11 ~ 2026-02-09", result.get("range"));

        ActivityCoverage coverage = (ActivityCoverage) result.get("coverage");
        assertEquals(30, coverage.days());
        assertEquals(29, coverage.recordedDays());
        assertEquals(1, coverage.missingDays());
        assertEquals(1, coverage.sparseDays());

        List<DailyActivityDay> days = days(result);
        assertEquals(29, days.size());
        // 缺数据的那天不会补一个 0 出来
        assertTrue(days.stream().noneMatch(d -> d.date().equals(START.plusDays(MISSING_INDEX))));

        // 距离从米换成公里（两位小数），热量保留一位
        DailyActivityDay last = days.get(days.size() - 1);
        assertEquals(LocalDate.of(2026, 2, 9), last.date());
        assertEquals(9900, last.steps());
        assertEquals(7.92, last.distanceKm(), 0.001);
        assertEquals(396.0, last.caloriesKcal(), 0.001);
        assertTrue(last.stepsGoalMet());
        // i=39 是奇数日（中高强度只有 10 分钟），但 39 能被 3 整除，站立是 12 次
        assertFalse(last.activeMinutesGoalMet());
        assertTrue(last.standGoalMet());
    }

    @Test
    void summaryUsesRecordedDaysAsDenominator() {
        Map<String, Object> result = tools.getDailyActivity("2026-01-01", "2026-02-09", null);
        ActivitySummary summary = (ActivitySummary) result.get("summary");

        long expectedSteps = 0;
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < SEED_DAYS; i++) {
            if (i != MISSING_INDEX) {
                expectedSteps += stepsAt(i);
                values.add(stepsAt(i));
            }
        }
        values.sort(Integer::compareTo);

        assertEquals(39, summary.days());
        assertEquals(expectedSteps, summary.totalSteps());
        assertEquals(Math.round((double) expectedSteps / 39), summary.averageSteps());
        // 39 个值取正中间那个
        assertEquals((int) values.get(19), summary.medianSteps());
        assertEquals(3000, summary.minSteps());
        assertEquals(START.plusDays(LOW_INDEX), summary.minStepsDate());
        assertEquals(9900, summary.maxSteps());
        assertEquals(START.plusDays(39), summary.maxStepsDate());
        assertEquals(1, summary.lowActivityDays());
    }

    @Test
    void countsGoalDaysAndWeeklyActiveMinutes() {
        Map<String, Object> result = tools.getDailyActivity("2026-01-01", "2026-02-09", null);
        ActivityGoals goals = (ActivityGoals) result.get("goals");

        assertEquals(8000, goals.stepsPerDay());
        assertEquals(30, goals.activeMinutesPerDay());
        assertEquals(12, goals.standEventsPerDay());
        assertEquals(150, goals.activeMinutesPerWeek());
        // 步数 ≥8000 的是 i≥20（20 天），久坐日 i=5 与缺失的 i=19 不在其中
        assertEquals(20, goals.daysMeetingSteps());
        // 中高强度 30 分钟的是偶数日，0..38 共 20 天，缺的 i=19 是奇数
        assertEquals(20, goals.daysMeetingActiveMinutes());
        // 站立 12 次的是 i=0,3,...,39 共 14 天
        assertEquals(14, goals.daysMeetingStand());
        // 末尾 7 天 i=33..39：偶数日 34/36/38 各 30 分钟 + 四个奇数日各 10 分钟
        assertEquals(7, goals.weeklyActiveMinutesDays());
        assertEquals(130, goals.weeklyActiveMinutes());
        assertFalse(goals.weeklyActiveMinutesMet());
    }

    @Test
    void trendComparesLastSevenDaysWithPreviousSeven() {
        Map<String, Object> result = tools.getDailyActivity("2026-01-01", "2026-02-09", null);
        @SuppressWarnings("unchecked")
        Map<String, ActivityTrend> trends = (Map<String, ActivityTrend>) result.get("trends");

        ActivityTrend steps = trends.get("steps");
        assertNotNull(steps);
        assertEquals("步", steps.unit());
        assertEquals(7, steps.recentDays());
        assertEquals(7, steps.previousDays());
        // 最近 7 天 i=33..39（9300 ~ 9900）均值 9600，前 7 天 i=26..32 均值 8900
        assertEquals(9600.0, steps.recent(), 0.001);
        assertEquals(8900.0, steps.previous(), 0.001);
        assertEquals(7.9, steps.changePercent(), 0.051);
        assertEquals("up", steps.direction());

        ActivityTrend stand = trends.get("standEvents");
        // 站立 3 天一循环，两个 7 天窗口的合计差 4 次（68 vs 64），也判成 up
        assertEquals("up", stand.direction());
        assertEquals(6.3, stand.changePercent(), 0.11);
    }

    @Test
    void trendIsFlatWhenBothWindowsMatch() {
        // 额外种 14 天完全一样的数据（2026-03-01 ~ 03-14），两个 7 天窗口一模一样
        for (int i = 0; i < 14; i++) {
            insert(LocalDate.of(2026, 3, 1).plusDays(i), 7000, 6500, 500, 5600, 280, 25, 10, 200);
        }

        Map<String, Object> result = tools.getDailyActivity("2026-03-01", "2026-03-14", null);
        @SuppressWarnings("unchecked")
        Map<String, ActivityTrend> trends = (Map<String, ActivityTrend>) result.get("trends");

        ActivityTrend steps = trends.get("steps");
        assertEquals("flat", steps.direction());
        assertEquals(0.0, steps.changePercent(), 0.001);
        assertEquals(7000.0, steps.recent(), 0.001);
    }

    @Test
    void weeklyPeriodsMarkTruncatedBoundaries() {
        Map<String, Object> result = tools.getDailyActivity("2026-01-01", "2026-02-09", "week");

        assertEquals("week", result.get("groupBy"));
        assertNull(result.get("days"));
        @SuppressWarnings("unchecked")
        List<ActivityPeriod> periods = (List<ActivityPeriod>) result.get("periods");
        assertNotNull(periods);
        assertEquals(periods.size(), result.get("periodCount"));

        // 2026-01-01 是周四，ISO 周从 2025-12-29（周一）起算，被查询区间截断
        ActivityPeriod first = periods.get(0);
        assertEquals("2026-W01", first.period());
        assertFalse(first.complete());
        assertEquals(LocalDate.of(2025, 12, 29), first.from());

        // 2026-02-02（周一）~ 02-08 是完整的一周
        ActivityPeriod w06 = periods.stream()
                .filter(p -> "2026-W06".equals(p.period())).findFirst().orElseThrow();
        assertTrue(w06.complete());
        assertEquals(7, w06.days());

        // 02-09 是周一，自成最后一个被截断的周期
        ActivityPeriod last = periods.get(periods.size() - 1);
        assertEquals("2026-W07", last.period());
        assertEquals(1, last.days());
        assertFalse(last.complete());
    }

    @Test
    void reportsSparseAndMissingDaysInNotes() {
        Map<String, Object> result = tools.getDailyActivity("2026-01-01", "2026-02-09", null);
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) result.get("notes");

        assertTrue(notes.stream().anyMatch(n -> n.contains("1 天没有活动记录")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("分钟数据不足")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("低于 5000")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("站立（standEvents）是「站立事件条数」")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("WHO")));
    }

    @Test
    void rejectsInvalidArguments() {
        Map<String, Object> badGroup = tools.getDailyActivity(null, null, "quarter");
        assertEquals("INVALID_ARGUMENT", badGroup.get("status"));
        assertEquals("groupBy", badGroup.get("param"));

        Map<String, Object> badDate = tools.getDailyActivity("2026-13-01", null, null);
        assertEquals("INVALID_ARGUMENT", badDate.get("status"));
        assertEquals("startDate", badDate.get("param"));

        Map<String, Object> reversed = tools.getDailyActivity("2026-02-09", "2026-01-01", null);
        assertEquals("INVALID_ARGUMENT", reversed.get("status"));

        // 逐日粒度超过 92 天要拒绝，并提示改用 week / month
        Map<String, Object> tooLong = tools.getDailyActivity("2025-01-01", "2026-02-09", "day");
        assertEquals("INVALID_ARGUMENT", tooLong.get("status"));
        assertTrue(String.valueOf(tooLong.get("message")).contains("groupBy=week"));
    }

    @Test
    void returnsNoDataWithCoveredRange() {
        Map<String, Object> result = tools.getDailyActivity("2026-06-01", "2026-06-30", null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-01-01 ~ 2026-02-09", result.get("coveredRange"));
        assertEquals("2026-06-01", result.get("from"));
    }

    @SuppressWarnings("unchecked")
    private static List<DailyActivityDay> days(Map<String, Object> result) {
        return (List<DailyActivityDay>) result.get("days");
    }
}
