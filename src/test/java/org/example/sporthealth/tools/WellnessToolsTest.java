package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.HrvNight;
import org.example.sporthealth.domain.HrvPeriod;
import org.example.sporthealth.domain.Spo2Day;
import org.example.sporthealth.domain.Spo2LowEvent;
import org.example.sporthealth.domain.StressBucket;
import org.example.sporthealth.domain.VitalsSample;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.VitalsMetricStore;
import org.example.sporthealth.service.BloodOxygenService;
import org.example.sporthealth.service.HrvTrendService;
import org.example.sporthealth.service.StressSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * HRV / 压力 / 血氧三个工具的口径。
 *
 * <p>这里守的核心是<b>「一夜不等于一个日历日」</b>：故意让睡眠窗口内的值与日间值差很大，
 * 只要实现退回「按 {@code substr(start_time,1,10)} 分组」，断言立刻失败。
 * 用真实 SQLite 而不是 mock —— 要验的是时间字符串的边界比较、跨日归属、按天归组，
 * mock 全都测不出来。
 */
class WellnessToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private WellnessTools tools;

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

        seedHrv();
        seedStress();
        seedSpo2();
        tools = new WellnessTools(
                new HrvTrendService(new VitalsMetricStore(jdbc)),
                new StressSeriesService(new VitalsMetricStore(jdbc)),
                new BloodOxygenService(new VitalsMetricStore(jdbc)));
    }

    // ------------------------------------------------------------------ HRV

    /**
     * 五夜，刻意让「睡眠窗口内」与「日间」差得很远：
     * <ul>
     *   <li>{@code 03-05} 夜：睡 22:00–06:00，窗口内 6 条 40；日间 10 条 80。
     *       如果按日历日全天平均会得到 ~60 左右，按会话窗口才是 40；</li>
     *   <li>{@code 03-06} 夜：窗口内 12 条 50；</li>
     *   <li>{@code 03-07} 夜：窗口内 12 条 60；</li>
     *   <li>{@code 03-08} 夜：只有 3 条（不足 10 条），应被排除；</li>
     *   <li>{@code 03-09} 夜：窗口内 12 条 55，但日间只有 1 条 200 —— 日间均值必须为空。</li>
     * </ul>
     * 03-02 是周一，所以 03-05/06/07/08 同属 2026-W10，03-09 属 2026-W11。
     */
    private void seedHrv() {
        // 03-05 夜：会话 03-04 22:00 ~ 03-05 06:00，窗口内全 40
        sleepSession("2026-03-04 22:00:00", "2026-03-05 06:00:00", "2026-03-05", 420);
        hrv("2026-03-04T22:30", 40, 6);   // 睡眠窗口内（前一天 22:30）
        hrv("2026-03-05T05:00", 40, 6);   // 睡眠窗口内（当天 05:00）
        hrv("2026-03-05T09:00", 80, 10);  // 日间，不属于睡眠窗口

        // 03-06 夜
        sleepSession("2026-03-05 22:30:00", "2026-03-06 05:30:00", "2026-03-06", 400);
        hrv("2026-03-06T01:00", 50, 12);
        hrv("2026-03-06T10:00", 70, 8);

        // 03-07 夜
        sleepSession("2026-03-06 23:00:00", "2026-03-07 06:00:00", "2026-03-07", 400);
        hrv("2026-03-07T02:00", 60, 12);
        hrv("2026-03-07T12:00", 75, 8);

        // 03-08 夜：样本不足
        sleepSession("2026-03-07 23:00:00", "2026-03-08 06:00:00", "2026-03-08", 400);
        hrv("2026-03-08T02:00", 45, 3);

        // 03-09 夜：日间只有 1 条样本
        sleepSession("2026-03-08 23:00:00", "2026-03-09 06:00:00", "2026-03-09", 400);
        hrv("2026-03-09T02:00", 55, 12);
        hrv("2026-03-09T15:00", 200, 1);
    }

    /** 在 {@code at} 起每分钟一条，共 {@code count} 条。 */
    private void hrv(String at, int value, int count) {
        for (int i = 0; i < count; i++) {
            metric("HEART_RATE_VARIABILITY", 500044,
                    plusMinutes(at, i), String.valueOf((double) value), null, "0");
        }
    }

    @Test
    void sleepWindowDecidesTheNightNotCalendarDay() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-05", "2026-03-05", null, null);
        assertEquals("OK", result.get("status"));

        List<HrvNight> nightly = nightly(result);
        assertEquals(1, nightly.size());
        HrvNight night = nightly.get(0);
        assertEquals("2026-03-05", night.date().toString());
        assertEquals("22:00", night.sleepStart());
        assertEquals("06:00", night.sleepEnd());
        // 窗口内 12 条全是 40；如果按日历日平均，会被日间的 10 条 80 拉高
        assertEquals(12, night.sleepSamples());
        assertEquals(40.0, night.rmssd(), 1e-9, "睡眠窗口均值必须是 40，不能被日间的 80 拉高");
        assertEquals(80.0, night.dayRmssd(), 1e-9);
        assertEquals(10, night.daySamples());
        // 日历日口径：当天 05:00 的 6 条 40 + 日间 10 条 80 → 65
        assertEquals(65.0, night.wholeDayRmssd(), 1e-9,
                "全天口径要与 daily_vitals 对齐，它是 65 而不是 40");
    }

    @Test
    void dayRmssdHiddenWhenTooFewDaySamples() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-09", "2026-03-09", null, null);
        HrvNight night = nightly(result).get(0);
        assertEquals(1, night.daySamples());
        assertNull(night.dayRmssd(), "只有 1 条日间样本时不给均值，否则 200 会被读成真实水平");
        assertEquals(55.0, night.rmssd(), 1e-9);
    }

    @Test
    void sparseNightIsExcludedButStillCounted() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-08", "2026-03-08", null, null);
        // 只有 3 条，不算有效夜
        assertEquals("NO_DATA", result.get("status"));
        // 全库范围是样本首末，不是睡眠会话的首末
        assertEquals("2026-03-04 ~ 2026-03-09", result.get("coveredRange"));
    }

    @Test
    void weekAggregationMarksTruncatedPeriods() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-05", "2026-03-09", null, Boolean.FALSE);
        assertEquals("OK", result.get("status"));
        assertEquals("week", result.get("period"));
        assertEquals("sleep", result.get("basis"));
        assertEquals("ms", result.get("unit"));

        @SuppressWarnings("unchecked")
        List<HrvPeriod> periods = (List<HrvPeriod>) result.get("periods");
        assertEquals(2, periods.size(), "03-05~03-08 是 W10，03-09 是 W11");

        HrvPeriod first = periods.get(0);
        assertEquals("2026-W10", first.period());
        assertEquals("2026-03-05", first.from().toString(), "被区间截断，起点是查询起点而不是周一");
        assertFalse(first.complete());
        assertEquals(3, first.nights(), "03-08 那夜的 3 条样本不算有效夜");
        // 40、50、60 三夜
        assertEquals(50.0, first.rmssd(), 1e-9);

        HrvPeriod second = periods.get(1);
        assertEquals("2026-W11", second.period());
        assertFalse(second.complete());
        assertEquals(1, second.nights());
    }

    @Test
    void risingHrvIsImproving() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-05", "2026-03-09", null, Boolean.FALSE);
        // 只有 1~3 个夜、2 个周期，趋势可能判不了，但方向语义必须是 up→improving
        Object direction = result.get("direction");
        if ("up".equals(direction)) {
            assertEquals("improving", result.get("assessment"),
                    "HRV 上升是改善，不能照搬静息心率那套");
        }
        assertEquals(4, result.get("nights"),
                "五夜里有 03-08 那夜样本不足被排除，剩 03-05/06/07/09 四夜");
    }

    @Test
    void monthPeriodIsAccepted() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-05", "2026-03-09", "month", Boolean.FALSE);
        assertEquals("OK", result.get("status"));
        assertEquals("month", result.get("period"));
        @SuppressWarnings("unchecked")
        List<HrvPeriod> periods = (List<HrvPeriod>) result.get("periods");
        assertEquals(1, periods.size());
        assertEquals("2026-03", periods.get(0).period());
        assertFalse(periods.get(0).complete());
    }

    @Test
    void invalidPeriodIsRejectedNotDefaulted() {
        Map<String, Object> result = tools.getHrvTrend("2026-03-05", "2026-03-09", "day", null);
        assertEquals("INVALID_ARGUMENT", result.get("status"));
        assertEquals("period", result.get("param"));
        assertEquals("day", result.get("value"));
        assertTrue(String.valueOf(result.get("allowedValues")).contains("week"));
    }

    // --------------------------------------------------------------- 压力

    /**
     * 两天：
     * <ul>
     *   <li>{@code 03-05}：每 30 分钟一条到 23:30（48 条），00:00 给 15（下限）、06:30 给 53（全库最高）；</li>
     *   <li>{@code 03-06}：只到 05:00，用来验证「没覆盖到晚上」的提示。</li>
     * </ul>
     */
    private void seedStress() {
        for (int i = 0; i < 48; i++) {
            int minute = i * 30;
            int value = 20 + (i % 4) * 6;
            if (i == 0) {
                value = 15;
            }
            if (i == 13) {
                value = 53;
            }
            metric("STRESS", 11, at("2026-03-05", minute), String.valueOf((double) value),
                    "{\"grade\":1,\"measureType\":1}", "0");
        }
        for (int i = 0; i < 11; i++) {
            metric("STRESS", 11, at("2026-03-06", i * 30), "25", "{\"grade\":1}", "0");
        }
    }

    @Test
    void stressBucketsAndLevels() {
        Map<String, Object> result = tools.getStressSeries("2026-03-05", null);
        assertEquals("OK", result.get("status"));
        assertEquals("2026-03-05", result.get("date"));
        assertEquals(60, result.get("bucketMinutes"));
        assertEquals(48, result.get("samples"));
        assertEquals(15, result.get("min"));
        assertEquals(53, result.get("max"));
        assertEquals(30, result.get("medianGapMinutes"));

        @SuppressWarnings("unchecked")
        List<StressBucket> buckets = (List<StressBucket>) result.get("buckets");
        assertEquals(24, buckets.size(), "48 条 30 分钟间隔 → 24 个 60 分钟桶");
        assertEquals("00:00", buckets.get(0).bucket());
        assertEquals(2, buckets.get(0).samples());
        // 00:00 的 15 与 00:30 的 26 → 20.5
        assertEquals(20.5, buckets.get(0).average(), 1e-9);
        assertEquals(26, buckets.get(0).max());
        assertEquals("relaxed", buckets.get(0).level());

        @SuppressWarnings("unchecked")
        Map<String, Integer> levels = (Map<String, Integer>) result.get("levels");
        assertTrue(levels.containsKey("relaxed"));
        assertTrue(levels.containsKey("normal"));
        assertFalse(levels.containsKey("high"), "全库最高 53 分，到不了 high 档");
        assertEquals(48, levels.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void stressPeakAndCalmestPicked() {
        Map<String, Object> result = tools.getStressSeries("2026-03-05", null);
        VitalsSample peak = (VitalsSample) result.get("peak");
        assertEquals(53.0, peak.value(), 1e-9);
        assertEquals("06:30", peak.time());
        VitalsSample calmest = (VitalsSample) result.get("calmest");
        assertEquals(15.0, calmest.value(), 1e-9);
        assertEquals("00:00", calmest.time());
    }

    @Test
    void stressNotesExplainFloorAndCoverage() {
        Map<String, Object> result = tools.getStressSeries("2026-03-05", null);
        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(note -> note.contains("算法下限")), String.valueOf(notes));

        Map<String, Object> shortDay = tools.getStressSeries("2026-03-06", null);
        List<String> shortNotes = notes(shortDay);
        assertTrue(shortNotes.stream().anyMatch(note -> note.contains("只覆盖")),
                "05:00 就结束的那天必须提示没覆盖到晚上：" + shortNotes);
    }

    @Test
    void stressResolutionIsValidated() {
        Map<String, Object> result = tools.getStressSeries("2026-03-05", "5min");
        assertEquals("INVALID_ARGUMENT", result.get("status"));
        assertEquals("resolution", result.get("param"));
        assertTrue(String.valueOf(result.get("allowedValues")).contains("30min"));
    }

    @Test
    void stressMissingDayCarriesCoveredRange() {
        Map<String, Object> result = tools.getStressSeries("2026-03-20", null);
        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-03-05 ~ 2026-03-06", result.get("coveredRange"));
    }

    // --------------------------------------------------------------- 血氧

    /**
     * {@code 03-05}：97 / 96 / 94（低于 95 一条），外加一次设备判定的低氧提醒；
     * {@code 03-06}：99 / 98。
     */
    private void seedSpo2() {
        metric("BLOOD_OXYGEN_SATURATION", 16, "2026-03-05T01:00", "97", null, "0");
        metric("BLOOD_OXYGEN_SATURATION", 16, "2026-03-05T01:10", "96", null, "0");
        metric("BLOOD_OXYGEN_SATURATION", 16, "2026-03-05T01:20", "94", null, "0");
        metric("BLOOD_OXYGEN_SATURATION", 16, "2026-03-06T02:00", "99", null, "0");
        metric("BLOOD_OXYGEN_SATURATION", 16, "2026-03-06T02:10", "98", null, "0");
        metric("LOW_BLOOD_OXYGEN", 400022, "2026-03-05T01:20", null,
                "{\"bloodOxygenRemind\":94.0,\"lowSpO2Threshold\":90.0}", "0");
    }

    @Test
    void bloodOxygenDailyAggregation() {
        Map<String, Object> result = tools.getBloodOxygen("2026-03-05", "2026-03-06");
        assertEquals("OK", result.get("status"));
        assertEquals(2, result.get("days"));
        assertEquals(5, result.get("records"));
        assertEquals("2026-03-05", result.get("firstDate"));
        assertEquals("2026-03-06", result.get("lastDate"));

        @SuppressWarnings("unchecked")
        List<Spo2Day> daily = (List<Spo2Day>) result.get("daily");
        Spo2Day first = daily.get(0);
        assertEquals(3, first.samples());
        assertEquals(94, first.min());
        assertEquals(97, first.max());
        assertEquals(95.7, first.average(), 1e-9);
        assertEquals(1, first.below95(), "只有 94 那条低于 95");
        assertEquals(2, daily.get(1).samples());
        assertEquals(0, daily.get(1).below95());
    }

    @Test
    void bloodOxygenLowEventsAreSeparateFromSamples() {
        Map<String, Object> result = tools.getBloodOxygen("2026-03-05", "2026-03-06");
        assertEquals(1, result.get("lowEventCount"));
        @SuppressWarnings("unchecked")
        List<Spo2LowEvent> events = (List<Spo2LowEvent>) result.get("lowEvents");
        assertEquals(94, events.get(0).value());
        assertEquals(90.0, events.get(0).threshold(), 1e-9);
        assertEquals("01:20", events.get(0).time());
    }

    @Test
    void bloodOxygenNotesSayMissingIsNotNormal() {
        Map<String, Object> result = tools.getBloodOxygen("2026-03-01", "2026-03-06");
        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(note -> note.contains("没有记录不等于血氧正常")),
                String.valueOf(notes));
        assertTrue(notes.stream().anyMatch(note -> note.contains("只有 2 天有记录")),
                "要说明区间内实际只有几天有数据：" + notes);
    }

    @Test
    void bloodOxygenEmptyRangeSaysWhereTheDataIs() {
        Map<String, Object> result = tools.getBloodOxygen("2026-04-01", "2026-04-30");
        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-03-05 ~ 2026-03-06", result.get("coveredRange"));
        assertTrue(String.valueOf(result.get("hint")).contains("连续血氧"));
    }

    // ------------------------------------------------------------ 共同校验

    @Test
    void reversedDatesAreRejected() {
        Map<String, Object> hrv = tools.getHrvTrend("2026-03-09", "2026-03-05", null, null);
        assertEquals("INVALID_ARGUMENT", hrv.get("status"));
        assertEquals("startDate", hrv.get("param"));

        Map<String, Object> spo2 = tools.getBloodOxygen("2026-03-09", "2026-03-05");
        assertEquals("INVALID_ARGUMENT", spo2.get("status"));
    }

    @Test
    void unparsableDateIsRejected() {
        Map<String, Object> hrv = tools.getHrvTrend("上周一", null, null, null);
        assertEquals("INVALID_ARGUMENT", hrv.get("status"));
        assertEquals("startDate", hrv.get("param"));
        assertEquals("上周一", hrv.get("value"));

        Map<String, Object> stress = tools.getStressSeries("03/05/2026", null);
        assertEquals("INVALID_ARGUMENT", stress.get("status"));
        assertEquals("date", stress.get("param"));
    }

    // -------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static List<HrvNight> nightly(Map<String, Object> result) {
        return (List<HrvNight>) result.get("nightly");
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> result) {
        return (List<String>) result.get("notes");
    }

    private void sleepSession(String start, String end, String sleepDate, int asleep) {
        jdbc.sql("""
                        INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                            in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                            awake_minutes, noon_minutes, segment_count)
                        VALUES (?, ?, ?, 'NIGHT', ?, ?, 0, 0, 0, 0, 0, 0)
                        """)
                .param(start).param(end).param(sleepDate).param(asleep).param(asleep)
                .update();
    }

    private void metric(String key, int type, String startTime, String value, String text, String unit) {
        String id = key + "-" + startTime + "-" + Math.abs(value == null ? 0 : value.hashCode());
        jdbc.sql("""
                        INSERT INTO health_metric (record_id, metric_key, start_time, end_time, type_code,
                            value_num, value_text, unit, device_code, time_zone, source_file)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2374625270', '+0800', 'test.json')
                        """)
                .param(id).param(key).param(startTime).param(startTime).param(type)
                .param(value == null ? null : Double.valueOf(value))
                .param(text).param(unit)
                .update();
    }

    private static String at(String date, int minuteOfDay) {
        return String.format("%sT%02d:%02d", date, minuteOfDay / 60, minuteOfDay % 60);
    }

    private static String plusMinutes(String at, int minutes) {
        String date = at.substring(0, 10);
        int hour = Integer.parseInt(at.substring(11, 13));
        int minute = Integer.parseInt(at.substring(14, 16));
        int total = hour * 60 + minute + minutes;
        return at(date, total);
    }
}
