package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.repository.DailyActivityStore;
import org.example.sporthealth.repository.SleepStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.repository.VitalsStore;
import org.example.sporthealth.service.DailyActivityService;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.service.SleepAnalyticsService;
import org.example.sporthealth.service.VitalsAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_recovery_status} 的语义：睡眠最新夜 / 体征均值 / 训练负荷覆盖度 /
 * 今日活动量四块各自取对应服务的值，覆盖缺省时是 null 不是 0。
 *
 * <p>用真实 SQLite 而不是 mock：这四张表的查询语义（覆盖度、均值分母、今日覆盖）
 * 全是数据库侧行为，mock 测不出来。
 */
class RecoveryToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private RecoveryTools tools;

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

        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("UTC"));
        seed();
        SleepAnalyticsService sleep = new SleepAnalyticsService(new SleepStore(jdbc), new DateRangeResolver(clock));
        VitalsAnalyticsService vitals = new VitalsAnalyticsService(new VitalsStore(jdbc), clock);
        DailyActivityService activity = new DailyActivityService(new DailyActivityStore(jdbc), clock);
        RunAnalyticsService runs = new RunAnalyticsService(
                new EmptyHealthDataRepository(), new DateRangeResolver(clock));
        tools = new RecoveryTools(sleep, vitals, activity, runs);
    }

    @Test
    void recoveryStatusCombinesFourBlocks() {
        Map<String, Object> result = tools.getRecoveryStatus(null, null);

        assertEquals("OK", result.get("status"));
        Map<?, ?> coverage = (Map<?, ?>) result.get("coverage");
        assertEquals(2, coverage.get("sleepNights"));
        assertEquals(2, coverage.get("vitalsDays"));

        Map<?, ?> latestSleep = (Map<?, ?>) result.get("latestSleep");
        assertEquals("2026-11-20", latestSleep.get("date"));
        assertEquals(80.0, (Double) latestSleep.get("score"));
        assertEquals(92.0, (Double) latestSleep.get("efficiencyPercent"));
        assertEquals(100, ((Number) latestSleep.get("deepMinutes")).intValue());

        Map<?, ?> vitals = (Map<?, ?>) result.get("vitals");
        assertEquals(64.0, ((Number) vitals.get("restingHeartRateAvg")).doubleValue());
        assertEquals(57.5, ((Number) vitals.get("hrvAvg")).doubleValue());
        assertEquals(2, ((Number) vitals.get("daysWithRestingHeartRate")).intValue());

        Map<?, ?> training = (Map<?, ?>) result.get("training");
        assertNotNull(training);
        assertEquals(0.0, ((Number) training.get("acuteLoadKm")).doubleValue());
        assertNotNull(training.get("riskLevel"));
        assertNotNull(training.get("recommendation"));

        assertEquals(2100, ((Number) result.get("stepsToday")).intValue());
        assertNotNull(((String) result.get("summary")));
    }

    @Test
    void recoveryStatusNoDataWhenEverythingEmpty() {
        // 空库：没有任何睡眠/体征/训练/活动数据
        Map<String, Object> result = tools.getRecoveryStatus("2026-01-01", "2026-01-02");
        assertEquals("NO_DATA", result.get("status"));
    }

    private void seed() {
        // 两个夜晚：11-18（会话）+ 11-19（会话 + 报告）
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                        in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                        awake_minutes, noon_minutes, segment_count)
                VALUES ('2026-11-18 23:00:00','2026-11-19 06:00:00','2026-11-19','NIGHT',
                        360, 300, 50, 250, 0, 10, 0, 1)
                """).update();
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                        in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                        awake_minutes, noon_minutes, segment_count)
                VALUES ('2026-11-19 22:30:00','2026-11-20 06:30:00','2026-11-20','NIGHT',
                        360, 420, 100, 260, 60, 60, 0, 1)
                """).update();
        jdbc.sql("""
                INSERT INTO sleep_report (record_id, start_time, end_time, sleep_date, sleep_score,
                        sleep_efficiency, sleep_latency, avg_hrv, avg_oxygen_saturation, deep_part,
                        wake_count, turn_over_count, fall_asleep_time, rising_time)
                VALUES ('r1','2026-11-20T06:30','2026-11-20T07:00','2026-11-20', 80, 92, 10, 50, -1, 0.48, 0, 0, NULL, NULL)
                """).update();

        jdbc.sql("""
                INSERT INTO daily_vitals (heart_date, resting_heart_rate, hrv_rmssd, avg_heart_rate,
                        min_heart_rate, max_heart_rate, heart_rate_samples, stress_avg)
                VALUES ('2026-11-19', 65, 60, 70, 50, 90, 150, 5)
                """).update();
        jdbc.sql("""
                INSERT INTO daily_vitals (heart_date, resting_heart_rate, hrv_rmssd, avg_heart_rate,
                        min_heart_rate, max_heart_rate, heart_rate_samples, stress_avg)
                VALUES ('2026-11-20', 63, 55, 72, 52, 88, 160, 3)
                """).update();

        jdbc.sql("""
                INSERT INTO daily_activity (activity_date, steps, walk_steps, run_steps,
                        distance_meters, calories_kcal, active_minutes, stand_events, minute_rows)
                VALUES ('2026-11-19', 1500, 1200, 300, 2.1, 150.0, 10, 3, 40)
                """).update();
        jdbc.sql("""
                INSERT INTO daily_activity (activity_date, steps, walk_steps, run_steps,
                        distance_meters, calories_kcal, active_minutes, stand_events, minute_rows)
                VALUES ('2026-11-20', 2100, 1900, 200, 2.4, 160.0, 12, 5, 45)
                """).update();
    }
}
