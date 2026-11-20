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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.SleepTrend;
import org.example.sporthealth.domain.SleepTrendPoint;
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
 * {@code get_sleep_trend} 的口径：周期边界怎么算、哪些周期进拟合、方向怎么判。
 *
 * <p>种子数据是「造出来的规律」而不是真实导出：每周的睡着时长分别是 400 / 430 / 460 分钟，
 * 这样斜率、首末变化、R² 都有唯一正确答案（每周期 +30 分钟、R²=1），
 * 断言才有意义——否则只能证明代码跑通。
 *
 * <p>另外特意让 10 月有 4 天没记录、11 月少 8 天：{@code complete} 说的是「周期有没有被查询区间截断」，
 * 不是「数据完不完整」，这两个概念混起来会把「设备没戴」误读成「区间没覆盖」。
 */
class SleepTrendToolsTest {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 2026-11-09 是周一，W46 由此开始。 */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

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
        Clock clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        tools = new SleepHealthTools(new SleepAnalyticsService(new SleepStore(jdbc),
                new DateRangeResolver(clock)));
    }

    /**
     * 夜晚排布：
     * <ul>
     *   <li>10-05 ~ 10-31：27 夜，每夜 370 分钟（10 月有 4 天没记录，但区间完整覆盖）；</li>
     *   <li>11-02：1 夜 380 分钟（W45 只有一夜）；</li>
     *   <li>W46 11-09~11-15：7 夜 400 分钟，每夜有一段中途清醒；</li>
     *   <li>W47 11-16~11-22：7 夜 430 分钟，有报告（评分 78）；</li>
     *   <li>W48 11-23~11-29：7 夜 460 分钟，有报告（评分 82）。</li>
     * </ul>
     * W46 的评分是 null，用来验「上一个周期没值时差值必须是 null，不是减 0」。
     */
    private void seed() {
        LocalDate day = LocalDate.of(2026, 10, 5);
        while (!day.isAfter(LocalDate.of(2026, 10, 31))) {
            night(day, 370);
            day = day.plusDays(1);
        }
        night(LocalDate.of(2026, 11, 2), 380);

        for (int i = 0; i < 7; i++) {
            LocalDate d = MONDAY.plusDays(i);
            night(d, 400);
            // 每夜 03:00-03:10 醒一次：7 夜 × 1 次 → 这一周平均醒来 1.0 次
            stage("PROFESSIONAL_SLEEP_WAKE", d.atTime(3, 0), d.atTime(3, 10));
        }
        for (int i = 0; i < 7; i++) {
            LocalDate d = MONDAY.plusDays(7 + i);
            night(d, 430);
            report(d, 400 + 30, 78d, 92d, 10d, 50d);
            // 这一周只有 4 夜中途醒来 → 平均 4/7 ≈ 0.6 次，介于 1.0 与 0 之间
            if (i < 4) {
                stage("PROFESSIONAL_SLEEP_WAKE", d.atTime(3, 0), d.atTime(3, 10));
            }
        }
        for (int i = 0; i < 7; i++) {
            LocalDate d = MONDAY.plusDays(14 + i);
            night(d, 460);
            report(d, 400 + 60, 82d, 94d, 8d, 54d);
        }
    }

    /** 一晚：00:00 睡到 asleep 分钟之后，卧床时长就等于睡着时长（清醒单独由 WAKE 分期表达）。 */
    private void night(LocalDate day, int asleep) {
        int deep = asleep * 3 / 10;
        int rem = asleep / 7;
        int light = asleep - deep - rem;
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = start.plusMinutes(asleep);
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                       in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                       awake_minutes, noon_minutes, segment_count)
                VALUES (:start, :end, :day, 'NIGHT', :inBed, :asleep, :deep, :light, :rem, 0, 0, 1)
                """)
                .param("start", TS.format(start)).param("end", TS.format(end))
                .param("day", day.toString()).param("inBed", asleep).param("asleep", asleep)
                .param("deep", deep).param("light", light).param("rem", rem)
                .update();
    }

    private void report(LocalDate day, int minutes, Double score, Double efficiency, Double latency,
                        Double hrv) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = start.plusMinutes(minutes);
        jdbc.sql("""
                INSERT INTO sleep_report (record_id, start_time, end_time, sleep_date, sleep_score,
                       sleep_efficiency, sleep_latency, avg_hrv, avg_oxygen_saturation, deep_part,
                       wake_count, turn_over_count, fall_asleep_time, rising_time, bed_time,
                       avg_heartrate, avg_breathrate, snore_freq)
                VALUES (:id, :start, :end, :day, :score, :eff, :latency, :hrv, -1, 100,
                        0, 0, :fallAsleep, :rising, :bedTime, 46, 15, 1)
                """)
                .param("id", "r-" + day).param("start", TS.format(start)).param("end", TS.format(end))
                .param("day", day.toString()).param("score", score).param("eff", efficiency)
                .param("latency", latency).param("hrv", hrv)
                .param("fallAsleep", start.toEpochSecond(ZONE))
                .param("rising", end.toEpochSecond(ZONE))
                .param("bedTime", start.toEpochSecond(ZONE))
                .update();
    }

    private void stage(String key, LocalDateTime start, LocalDateTime end) {
        jdbc.sql("""
                INSERT INTO health_metric (record_id, metric_key, start_time, end_time, type_code,
                       value_num, source_file)
                VALUES (:id, :key, :start, :end, 9, NULL, 'test.xls')
                """)
                .param("id", key + start).param("key", key)
                .param("start", TS.format(start)).param("end", TS.format(end))
                .update();
    }

    @Test
    void weeklyTrendFitsSlopeAndDeltas() {
        Map<String, Object> result = tools.getSleepTrend("2026-11-09", "2026-11-29", "week", null);
        assertEquals("OK", result.get("status"));
        assertEquals("week", result.get("groupBy"));
        assertEquals("asleep", result.get("metric"));
        assertEquals(3, result.get("periodCount"));

        List<SleepTrendPoint> points = points(result);
        assertEquals("2026-W46", points.get(0).period());
        assertEquals(LocalDate.of(2026, 11, 9), points.get(0).from());
        assertEquals(LocalDate.of(2026, 11, 15), points.get(0).to());
        assertEquals(7, points.get(0).nights());
        assertEquals(7, points.get(0).expectedNights());
        assertTrue(points.get(0).complete(), "整周落在区间内");
        assertEquals(400, points.get(0).values().asleepMinutes().intValue());
        assertEquals("6h 40m", points.get(0).asleepText());
        assertNull(points.get(0).deltas().asleepMinutes(), "第一个周期没有上一个可比");

        assertEquals(430, points.get(1).values().asleepMinutes().intValue());
        assertEquals(30, points.get(1).deltas().asleepMinutes().intValue());
        assertEquals(460, points.get(2).values().asleepMinutes().intValue());
        assertEquals(30, points.get(2).deltas().asleepMinutes().intValue());

        SleepTrend trend = (SleepTrend) result.get("trend");
        assertEquals("asleep", trend.metric());
        assertEquals("minutes", trend.unit());
        assertTrue(trend.higherIsBetter());
        assertEquals(3, trend.periodsUsed());
        assertEquals(0, trend.periodsExcluded());
        assertEquals("2026-W46", trend.first().period());
        assertEquals(400d, trend.first().value(), 1e-9);
        assertEquals("2026-W48", trend.last().period());
        assertEquals(460d, trend.last().value(), 1e-9);
        assertEquals(60d, trend.change(), 1e-9);
        assertEquals(15d, trend.changePercent(), 1e-9);
        assertEquals(30d, trend.slopePerPeriod(), 1e-9, "每周 +30 分钟");
        assertEquals(1d, trend.r2(), 1e-9, "三点完美共线");
        assertEquals("up", trend.direction());
        assertEquals("improving", trend.assessment());
        assertEquals("2026-W48", trend.best().period());
        assertEquals("2026-W46", trend.worst().period());
        assertEquals("leastSquaresOverCompletePeriods", trend.method());

        assertNull(result.get("notes"), "完整周期、方向明确、R²=1，不该有任何提示");
    }

    @Test
    void periodCutByRangeIsMarkedAndExcluded() {
        // 从周三开始：W46 只剩 11-11 ~ 11-15 五天
        Map<String, Object> result = tools.getSleepTrend("2026-11-11", "2026-11-29", "week", null);
        List<SleepTrendPoint> points = points(result);

        SleepTrendPoint first = points.get(0);
        assertEquals("2026-W46", first.period());
        assertEquals(LocalDate.of(2026, 11, 9), first.from(), "from 仍是周期边界，不是有数据的第一天");
        assertEquals(5, first.expectedNights(), "区间只覆盖这一周的后 5 天");
        assertEquals(5, first.nights());
        assertFalse(first.complete());

        SleepTrend trend = (SleepTrend) result.get("trend");
        assertEquals(2, trend.periodsUsed(), "被截断的首周不进拟合");
        assertEquals(1, trend.periodsExcluded());
        assertEquals("2026-W47", trend.first().period());
        assertEquals(30d, trend.slopePerPeriod(), 1e-9);

        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) result.get("notes");
        assertNotNull(notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains("未参与拟合")), "必须说明为什么少了一个周期");
        assertTrue(notes.stream().anyMatch(note -> note.contains("截断")));
    }

    @Test
    void monthlyTrendKeepsCompleteSeparateFromMissingNights() {
        Map<String, Object> result = tools.getSleepTrend("2026-10-01", "2026-11-30", "month", null);
        assertEquals(2, result.get("periodCount"));

        List<SleepTrendPoint> points = points(result);
        SleepTrendPoint october = points.get(0);
        assertEquals("2026-10", october.period());
        assertEquals(LocalDate.of(2026, 10, 1), october.from());
        assertEquals(LocalDate.of(2026, 10, 31), october.to());
        assertEquals(27, october.nights(), "27 夜有记录");
        assertEquals(31, october.expectedNights(), "整月都在区间内");
        assertTrue(october.complete(), "缺 4 天记录不等于周期被截断");
        assertEquals(370, october.values().asleepMinutes().intValue());

        SleepTrendPoint november = points.get(1);
        assertEquals("2026-11", november.period());
        assertEquals(22, november.nights());
        assertEquals(30, november.expectedNights());
        assertTrue(november.complete());
        // (380 + 400×7 + 430×7 + 460×7) / 22 = 427.7
        assertEquals(428, november.values().asleepMinutes().intValue());

        SleepTrend trend = (SleepTrend) result.get("trend");
        assertEquals(2, trend.periodsUsed());
        assertEquals(58d, trend.slopePerPeriod(), 1e-9);
        assertEquals("up", trend.direction(), "月粒度按累计变化判定，58 分钟不能被当成平稳");
        assertEquals("improving", trend.assessment());
    }

    @Test
    void metricWithoutDataIsExcludedAndItsDeltaStaysNull() {
        Map<String, Object> result = tools.getSleepTrend("2026-11-09", "2026-11-29", "week", "score");
        assertEquals("score", result.get("metric"));

        List<SleepTrendPoint> points = points(result);
        assertNull(points.get(0).values().score(), "W46 没有报告");
        assertEquals(0, points.get(0).reportedNights());
        assertEquals(78d, points.get(1).values().score(), 1e-9);
        assertNull(points.get(1).deltas().score(), "上一个周期没值，差值必须是 null 而不是减 0");
        assertEquals(4d, points.get(2).deltas().score(), 1e-9, "82 - 78");

        SleepTrend trend = (SleepTrend) result.get("trend");
        assertEquals(2, trend.periodsUsed());
        assertEquals(1, trend.periodsExcluded());
        assertEquals("points", trend.unit());
        assertEquals(4d, trend.change(), 1e-9);
        assertEquals("up", trend.direction());

        // 同一批数据的效率与 HRV 也只在有报告的周期上有值
        assertEquals(92d, points.get(1).values().efficiencyPercent(), 1e-9);
        assertEquals(54d, points.get(2).values().hrvMs(), 1e-9);
        assertEquals(8d, points.get(2).values().latencyMinutes(), 1e-9);
    }

    @Test
    void lowerIsBetterMetricMapsDropToImproving() {
        Map<String, Object> result = tools.getSleepTrend("2026-11-09", "2026-11-29", "week", "wake_count");
        assertEquals("wakeCount", result.get("metric"));

        List<SleepTrendPoint> points = points(result);
        assertEquals(1.0, points.get(0).values().wakeCount(), 1e-9, "7 夜各醒一次");
        assertEquals(0.6, points.get(1).values().wakeCount(), 1e-9, "7 夜里 4 夜醒过");
        assertEquals(0.0, points.get(2).values().wakeCount(), 1e-9);

        SleepTrend trend = (SleepTrend) result.get("trend");
        assertFalse(trend.higherIsBetter());
        assertEquals(-0.5, trend.slopePerPeriod(), 1e-9);
        assertEquals("down", trend.direction());
        assertEquals("improving", trend.assessment(), "醒来次数下降是变好，不能只看 down");
        assertEquals("2026-W48", trend.best().period(), "最好 = 醒来最少");
        assertEquals("2026-W46", trend.worst().period());
    }

    @Test
    void rejectDayGranularityAndUnknownMetric() {
        Map<String, Object> byDay = tools.getSleepTrend("2026-11-09", "2026-11-29", "day", null);
        assertEquals("INVALID_ARGUMENT", byDay.get("status"));
        assertEquals("groupBy", byDay.get("param"));
        assertEquals(List.of("week", "month"), byDay.get("allowedValues"));

        Map<String, Object> badMetric = tools.getSleepTrend(null, null, null, "steps");
        assertEquals("INVALID_ARGUMENT", badMetric.get("status"));
        assertEquals("metric", badMetric.get("param"));
        assertEquals("steps", badMetric.get("value"));
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) badMetric.get("allowedValues");
        assertTrue(allowed.contains("asleep") && allowed.contains("wakeCount"));

        Map<String, Object> reversed = tools.getSleepTrend("2026-11-29", "2026-11-09", null, null);
        assertEquals("INVALID_ARGUMENT", reversed.get("status"));
        assertEquals("startDate", reversed.get("param"));

        Map<String, Object> empty = tools.getSleepTrend("2030-01-01", "2030-01-31", null, null);
        assertEquals("NO_DATA", empty.get("status"));
        assertEquals("2026-10-05 ~ 2026-11-29", empty.get("coveredRange"));
    }

    @Test
    void singlePeriodHasNoSlope() {
        Map<String, Object> result = tools.getSleepTrend("2026-11-23", "2026-11-29", null, null);
        SleepTrend trend = (SleepTrend) result.get("trend");
        assertEquals(1, trend.periodsUsed());
        assertNull(trend.slopePerPeriod());
        assertNull(trend.r2());
        assertNull(trend.change());
        assertEquals("unknown", trend.direction());
        assertEquals("unknown", trend.assessment());
        assertEquals("leastSquaresOverAvailablePeriods", trend.method(), "完整周期不足 2 个，退回用现有周期");

        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) result.get("notes");
        assertTrue(notes.stream().anyMatch(note -> note.contains("不足 2 个")));
    }

    @Test
    void tooManyPeriodsAreRejected() {
        // 从 2024-06-03 起连续 105 个周一各插一夜；与上面 seed 的 10/11 月不重叠，
        // 加上那几周后周粒度点数必然超过 104
        LocalDate monday = LocalDate.of(2024, 6, 3);
        for (int i = 0; i < 105; i++) {
            night(monday.plusWeeks(i), 400);
        }

        Map<String, Object> result = tools.getSleepTrend(null, null, "week", null);
        assertEquals("INVALID_ARGUMENT", result.get("status"));
        assertEquals("groupBy", result.get("param"));
        String message = (String) result.get("message");
        assertTrue(message.contains("超过上限"), message);
        assertTrue(message.contains("month"), "要提示改用月粒度：" + message);
    }

    @SuppressWarnings("unchecked")
    private static List<SleepTrendPoint> points(Map<String, Object> result) {
        return (List<SleepTrendPoint>) result.get("points");
    }
}
