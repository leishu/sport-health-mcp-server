package org.example.sporthealth.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 派生表的重建语义：体重 JSON 拆列、睡眠碎片粘成会话、按天汇总步数与心率。
 *
 * <p>用真实 SQLite 文件而不是 mock：{@code json_extract}、窗口函数 {@code LAG/SUM OVER}、
 * {@code julianday} 的时间差都是数据库侧的语义，mock 测不到；这些也正是最容易写错的地方。
 */
class DerivedTablesTest {

    private static final String SOURCE = "health detail data1794280204067_0.json";
    private static final String MINUTE_SOURCE = "sport per minute merged data.json";

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private DerivedTables derived;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("db").resolve("sport-health.db");
        Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);

        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqliteSchema schema = new SqliteSchema(jdbc, dataSource);
        schema.ensure();
        derived = new DerivedTables(jdbc, transactions, schema);
    }

    @Test
    void splitsWeightJsonIntoTypedColumns() {
        // 体脂秤的完整形态：体重 + 全套体成分
        metric("w1", "WEIGHT_BODYFAT_BROAD", 8, "2026-11-09T05:47:01.809", "2026-11-09T05:47:01.809", 72.0, """
                {"age":52,"basalMetabolism":1764.0,"bmi":20.7,"bodyAge":42,"bodyFat":8.064,
                 "bodyFatRate":11.2,"bodyScore":87.0,"bodyWeight":72.0,"boneSalt":3.28,"gender":1,
                 "height":186,"heartRate":70,"moistureRate":66.0,"proteinRate":13.12,
                 "skeletalMusclelMass":34.61,"visceralFatLevel":4.0,"waistHipRatio":0.87}
                """);
        // 普通体重秤形态：只有体重，没有体成分
        metric("w2", "WEIGHT_BODYFAT_BROAD", 8, "2026-11-05T07:44:38.988", "2026-11-05T07:44:38.988", 68.1, """
                {"age":53,"bodyWeight":68.1,"gender":0,"height":168}
                """);

        DerivedTables.Counts counts = derived.rebuild();

        assertEquals(2, counts.bodyMeasurements());
        Map<String, Object> full = row("SELECT * FROM body_measurement WHERE record_id = 'w1'");
        assertEquals(72.0, ((Number) full.get("weight_kg")).doubleValue(), 0.001);
        assertEquals(20.7, ((Number) full.get("bmi")).doubleValue(), 0.001);
        assertEquals(11.2, ((Number) full.get("body_fat_percent")).doubleValue(), 0.001);
        assertEquals(8.064, ((Number) full.get("body_fat_kg")).doubleValue(), 0.001);
        assertEquals(34.61, ((Number) full.get("muscle_mass_kg")).doubleValue(), 0.001);
        assertEquals(1764.0, ((Number) full.get("basal_metabolism_kcal")).doubleValue(), 0.001);
        assertEquals(1, ((Number) full.get("has_body_composition")).intValue());
        assertEquals("2026-11-09T05:47:01", full.get("measured_at"));

        Map<String, Object> scaleOnly = row("SELECT * FROM body_measurement WHERE record_id = 'w2'");
        assertEquals(68.1, ((Number) scaleOnly.get("weight_kg")).doubleValue(), 0.001);
        assertEquals(0, ((Number) scaleOnly.get("has_body_composition")).intValue());
        assertNull(scaleOnly.get("bmi"));
    }

    @Test
    void stitchesMinuteSegmentsIntoOneSleepSession() {
        // 一夜：22:00 深睡 10 分钟 → 空 50 分钟（中途醒着但没记分期）→ 23:00 浅睡 10 分钟
        sleepSegments("PROFESSIONAL_SLEEP_DEEP", "2026-11-09T22:00", 10);
        sleepSegments("PROFESSIONAL_SLEEP_SHALLOW", "2026-11-09T23:00", 10);
        // 次日午睡：只有午睡分期
        sleepSegments("PROFESSIONAL_SLEEP_NOON", "2026-11-10T13:00", 20);

        DerivedTables.Counts counts = derived.rebuild();

        assertEquals(2, counts.sleepSessions());

        Map<String, Object> night = row("SELECT * FROM sleep_session WHERE sleep_type = 'NIGHT'");
        assertEquals("2026-11-09 22:00:00", night.get("session_start"));
        assertEquals("2026-11-09 23:10:00", night.get("session_end"));
        assertEquals("2026-11-09", night.get("sleep_date"));
        assertEquals(10, ((Number) night.get("deep_minutes")).intValue());
        assertEquals(10, ((Number) night.get("light_minutes")).intValue());
        assertEquals(20, ((Number) night.get("asleep_minutes")).intValue());
        assertEquals(70, ((Number) night.get("in_bed_minutes")).intValue());
        assertEquals(20, ((Number) night.get("segment_count")).intValue());

        Map<String, Object> nap = row("SELECT * FROM sleep_session WHERE sleep_type = 'NAP'");
        assertEquals("2026-11-10", nap.get("sleep_date"));
        assertEquals(20, ((Number) nap.get("noon_minutes")).intValue());
        assertEquals(20, ((Number) nap.get("asleep_minutes")).intValue());
    }

    @Test
    void splitsSessionsWhenGapExceedsThreeHours() {
        // 中间空了 4 小时（远大于夜里的最长清醒间隔），必须算两次睡眠而不是一次
        sleepSegments("PROFESSIONAL_SLEEP_DEEP", "2026-11-07T23:00", 5);
        sleepSegments("PROFESSIONAL_SLEEP_SHALLOW", "2026-11-08T03:30", 5);

        DerivedTables.Counts counts = derived.rebuild();

        assertEquals(2, counts.sleepSessions());
    }

    @Test
    void aggregatesMinutesIntoDailyActivity() {
        minute(20261109, "2026-11-09T08:00", "2026-11-09T08:01", 5, 120, 100, 5.0, 1, 0);
        minute(20261109, "2026-11-09T18:00", "2026-11-09T18:01", 4, 180, 400, 12.0, 1, 1);
        minute(20261109, "2026-11-09T09:00", "2026-11-09T09:01", 13, 0, 0, 1.5, 0, 1);
        minute(20261110, "2026-11-10T07:00", "2026-11-10T07:01", 5, 60, 50, 2.0, 0, 0);

        DerivedTables.Counts counts = derived.rebuild();

        assertEquals(2, counts.activityDays());
        Map<String, Object> day = row("SELECT * FROM daily_activity WHERE activity_date = '2026-11-09'");
        assertEquals(300, ((Number) day.get("steps")).intValue());
        assertEquals(120, ((Number) day.get("walk_steps")).intValue());
        assertEquals(180, ((Number) day.get("run_steps")).intValue());
        assertEquals(500, ((Number) day.get("distance_meters")).intValue());
        assertEquals(18.5, ((Number) day.get("calories_kcal")).doubleValue(), 0.001);
        assertEquals(2, ((Number) day.get("active_minutes")).intValue());
        // 站立只数 sportType=13 的事件条数：这条种子里只有 1 条，走路/跑步那两条的 is_stand
        // 不再参与计数（v9 之前按分钟求和，真实数据里时长恒为 0，算出来是 2.0 的假数）
        assertEquals(1, ((Number) day.get("stand_events")).intValue());
        assertEquals("2026-11-09T08:00", day.get("first_minute"));
        assertEquals("2026-11-09T18:01", day.get("last_minute"));
        assertEquals(3, ((Number) day.get("minute_rows")).intValue());
    }

    @Test
    void dedupesSameMinuteRecordedByTwoDevices() {
        // 同一分钟被两台设备各记一条：手表记成 sportType=5（120 步），手机记成 2（130 步）。
        // 逐行相加会算成 250 步，去重后只留步数大的那条。
        minute(20261109, "2026-11-09T06:02", "2026-11-09T06:03", 5, 120, 100, 5.0, 1, 0);
        minute(20261109, "2026-11-09T06:02", "2026-11-09T06:03", 2, 130, 110, 5.5, 1, 0);
        // 站立事件是整点独立事件，会和同一分钟的走路撞上，不能跟着走路一起被去重掉
        minute(20261109, "2026-11-09T07:00", "2026-11-09T07:01", 13, 0, 0, 0.0, 0, 1);
        minute(20261109, "2026-11-09T07:00", "2026-11-09T07:01", 5, 90, 60, 3.0, 0, 0);

        derived.rebuild();

        Map<String, Object> day = row("SELECT * FROM daily_activity WHERE activity_date = '2026-11-09'");
        assertEquals(220, ((Number) day.get("steps")).intValue());
        // sportType 2 与 5 都是走路：只认 5 会让换设备之后的走路步数凭空消失
        assertEquals(220, ((Number) day.get("walk_steps")).intValue());
        assertEquals(0, ((Number) day.get("run_steps")).intValue());
        assertEquals(170, ((Number) day.get("distance_meters")).intValue());
        assertEquals(8.5, ((Number) day.get("calories_kcal")).doubleValue(), 0.001);
        assertEquals(1, ((Number) day.get("active_minutes")).intValue());
        assertEquals(1, ((Number) day.get("stand_events")).intValue());
        // 4 条原始记录 → 3 条：06:02 合并成一条，07:00 的站立与走路各留一条
        assertEquals(3, ((Number) day.get("minute_rows")).intValue());
        assertEquals("2026-11-09T06:02", day.get("first_minute"));
        assertEquals("2026-11-09T07:01", day.get("last_minute"));
    }

    @Test
    void summarisesHeartRateAndStressPerDay() {
        // 静息心率一天多条，取最小值作为当日静息心率
        metric("r1", "RESTING_HEART_RATE", 500024, "2026-11-09T06:02", "2026-11-09T06:03", 49, "{\"restBpm\":49}");
        metric("r2", "RESTING_HEART_RATE", 500024, "2026-11-09T20:02", "2026-11-09T20:03", 43, "{\"restBpm\":43}");
        // 动态心率逐分钟采样
        metric("d1", "DYNAMIC_HEART_RATE", 7, "2026-11-09T08:00", "2026-11-09T08:01", 60, "60.0");
        metric("d2", "DYNAMIC_HEART_RATE", 7, "2026-11-09T12:00", "2026-11-09T12:01", 70, "70.0");
        metric("d3", "DYNAMIC_HEART_RATE", 7, "2026-11-09T18:00", "2026-11-09T18:01", 80, "80.0");
        metric("h1", "HEART_RATE_VARIABILITY", 500044, "2026-11-09T05:41", "2026-11-09T05:41", 50,
                "{\"heartRateVariabilityRMSSD\":50}");
        metric("h2", "HEART_RATE_VARIABILITY", 500044, "2026-11-09T06:07", "2026-11-09T06:07", 60,
                "{\"heartRateVariabilityRMSSD\":60}");
        metric("s1", "STRESS", 11, "2026-11-09T10:00", "2026-11-09T10:01", 30, "{\"score\":30}");
        metric("s2", "STRESS", 11, "2026-11-09T14:00", "2026-11-09T14:01", 40, "{\"score\":40}");
        metric("v1", "VO2MAX", 400028, "2026-11-09T06:50", "2026-11-09T06:50", 58, "{\"vo2Max\":58}");

        DerivedTables.Counts counts = derived.rebuild();

        assertEquals(1, counts.vitalDays());
        Map<String, Object> vitals = row("SELECT * FROM daily_vitals WHERE heart_date = '2026-11-09'");
        assertEquals(43, ((Number) vitals.get("resting_heart_rate")).intValue());
        assertEquals(70.0, ((Number) vitals.get("avg_heart_rate")).doubleValue(), 0.001);
        assertEquals(60, ((Number) vitals.get("min_heart_rate")).intValue());
        assertEquals(80, ((Number) vitals.get("max_heart_rate")).intValue());
        assertEquals(3, ((Number) vitals.get("heart_rate_samples")).intValue());
        assertEquals(55.0, ((Number) vitals.get("hrv_rmssd")).doubleValue(), 0.001);
        assertEquals(35.0, ((Number) vitals.get("stress_avg")).doubleValue(), 0.001);
        assertEquals(40, ((Number) vitals.get("stress_max")).intValue());
        assertEquals(58.0, ((Number) vitals.get("vo2max")).doubleValue(), 0.001);
    }

    @Test
    void rebuildIsIdempotentAndClearsStaleRows() {
        metric("w1", "WEIGHT_BODYFAT_BROAD", 8, "2026-11-09T05:47:01", "2026-11-09T05:47:01", 72.0,
                "{\"bodyWeight\":72.0}");
        minute(20261109, "2026-11-09T08:00", "2026-11-09T08:01", 5, 100, 80, 4.0, 1, 0);

        DerivedTables.Counts first = derived.rebuild();
        DerivedTables.Counts second = derived.rebuild();

        assertEquals(first, second);
        assertEquals(1, second.bodyMeasurements());
        assertEquals(1, second.activityDays());

        // 源行被删掉之后重建，派生表不能留着旧记录
        jdbc.sql("DELETE FROM health_metric").update();
        assertEquals(0, derived.rebuild().bodyMeasurements());
        assertEquals(1, derived.counts().activityDays());
    }

    // ---------------------------------------------------------------- 夹具

    private void metric(String recordId, String key, int typeCode, String start, String end,
                        Number valueNum, String valueText) {
        jdbc.sql("""
                INSERT INTO health_metric (record_id, metric_key, start_time, end_time, type_code,
                                           value_num, value_text, unit, device_code, time_zone, source_file)
                VALUES (:recordId, :metricKey, :startTime, :endTime, :typeCode,
                        :valueNum, :valueText, '0', 'dev', '+0800', :sourceFile)
                """)
                .param("recordId", recordId)
                .param("metricKey", key)
                .param("startTime", start)
                .param("endTime", end)
                .param("typeCode", typeCode)
                .param("valueNum", valueNum)
                .param("valueText", valueText)
                .param("sourceFile", SOURCE)
                .update();
    }

    /** 从 {@code start} 起连续插 {@code minutes} 条一分钟一段的分期。 */
    private void sleepSegments(String key, String start, int minutes) {
        java.time.LocalDateTime cursor = java.time.LocalDateTime.parse(start);
        for (int i = 0; i < minutes; i++) {
            metric(key + "-" + cursor, key, 9, cursor.toString(), cursor.plusMinutes(1).toString(), null, "");
            cursor = cursor.plusMinutes(1);
        }
    }

    private void minute(int recordDay, String start, String end, int sportType, int steps,
                        int distance, double kcal, int intensity, int stand) {
        jdbc.sql("""
                INSERT INTO activity_minute (start_time, sport_type, record_day, end_time, steps,
                        distance_meters, calories_kcal, duration_minutes, is_intensity, is_stand,
                        altitude, device_code, source_file)
                VALUES (:startTime, :sportType, :recordDay, :endTime, :steps,
                        :distance, :kcal, 1, :intensity, :stand, 45.0, 'dev', :sourceFile)
                """)
                .param("startTime", start)
                .param("sportType", sportType)
                .param("recordDay", recordDay)
                .param("endTime", end)
                .param("steps", steps)
                .param("distance", distance)
                .param("kcal", kcal)
                .param("intensity", intensity)
                .param("stand", stand)
                .param("sourceFile", MINUTE_SOURCE)
                .update();
    }

    private Map<String, Object> row(String sql) {
        List<Map<String, Object>> rows = jdbc.sql(sql).query().listOfRows();
        assertEquals(1, rows.size(), "期望恰好一行：" + sql);
        return rows.get(0);
    }
}
