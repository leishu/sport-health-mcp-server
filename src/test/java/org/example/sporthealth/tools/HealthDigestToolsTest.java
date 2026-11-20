package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.VitalsMetricStat;
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
 * {@code get_weekly_health_report}：周报是四块各自一份服务的聚合——
 * 步数 / 睡眠均值 / 体征均值 / 训练记录，各块带覆盖度，没有则说明没有。
 */
class HealthDigestToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private HealthDigestTools tools;
    private Clock clock;

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

        clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("UTC"));
        seed();
        SleepAnalyticsService sleep = new SleepAnalyticsService(new SleepStore(jdbc), new DateRangeResolver(clock));
        VitalsAnalyticsService vitals = new VitalsAnalyticsService(new VitalsStore(jdbc), clock);
        DailyActivityService activity = new DailyActivityService(new DailyActivityStore(jdbc), clock);
        RunAnalyticsService runs = new RunAnalyticsService(
                new EmptyHealthDataRepository(), new DateRangeResolver(clock));
        tools = new HealthDigestTools(sleep, vitals, activity, runs);
    }

    @Test
    void weeklyReportAggregatesFourBlocks() {
        Map<String, Object> result = tools.getWeeklyHealthReport("2026-11-14", "2026-11-20");

        assertEquals("OK", result.get("status"));
        assertEquals("2026-11-14 ~ 2026-11-20", result.get("range"));

        Map<?, ?> steps = (Map<?, ?>) result.get("steps");
        assertEquals(3600, ((Number) steps.get("totalSteps")).intValue());
        assertEquals(1800, ((Number) steps.get("averageSteps")).intValue());

        Map<?, ?> sleep = (Map<?, ?>) result.get("sleep");
        assertEquals(2, sleep.get("nights"));
        assertEquals(360, ((Number) sleep.get("avgAsleepMinutes")).intValue());

        Map<?, ?> vitals = (Map<?, ?>) result.get("vitals");
        assertEquals(64.0, ((VitalsMetricStat) vitals.get("restingHeartRate")).average());
        assertEquals(57.5, ((VitalsMetricStat) vitals.get("hrv")).average());

        Map<?, ?> runs = (Map<?, ?>) result.get("runs");
        assertEquals(0, runs.get("runCount"));

        assertNotNull(result.get("text"));
        assertTrue(((String) result.get("text")).contains("步数 3600"));
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                        in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                        awake_minutes, noon_minutes, segment_count)
                VALUES ('2026-11-19 22:30:00','2026-11-20 06:30:00','2026-11-20','NIGHT',
                        360, 420, 100, 260, 60, 60, 0, 1)
                """).update();
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                        in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                        awake_minutes, noon_minutes, segment_count)
                VALUES ('2026-11-18 23:00:00','2026-11-19 06:00:00','2026-11-19','NIGHT',
                        360, 300, 50, 250, 0, 10, 0, 1)
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
