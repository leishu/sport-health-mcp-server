package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.HeartRateActivity;
import org.example.sporthealth.domain.HeartRateBucket;
import org.example.sporthealth.domain.HeartRateDayStats;
import org.example.sporthealth.domain.HeartRateExtreme;
import org.example.sporthealth.repository.HeartRateSeriesStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.service.HeartRateSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_heart_rate_series} 的口径：分桶边界、极值能不能当峰值读、采样密度怎么表述。
 *
 * <p>用真实 SQLite 而不是 mock：这里守的几条（字符串时间范围的右开区间、分桶的取整、
 * {@code run_activity} 与心率时段的对齐）都是数据库与时间计算的行为，mock 测不出来。
 */
class HeartRateHealthToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private HeartRateHealthTools tools;

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
        tools = new HeartRateHealthTools(new HeartRateSeriesService(new HeartRateSeriesStore(jdbc)));
    }

    /**
     * 三天，覆盖三种采样形态：
     * <ul>
     *   <li>{@code 2026-03-05}（最新日）：静态时 10 分钟一条、07:00–07:30 跑步段每分钟一条，
     *       峰值 152 落在 07:12；用来验证「稀疏采样」「峰值由运动解释」；</li>
     *   <li>{@code 2026-03-01}：全天每分钟一条（早期设备），18:30 单独一个 136，
     *       前后都是 50~54；用来验证「孤立尖峰不该当峰值」；</li>
     *   <li>{@code 2026-03-02}：只有 00:00–00:30 与 03:00–03:10，中间空 150 分钟；
     *       用来验证「覆盖不全」与「曲线断了」。</li>
     * </ul>
     */
    private void seed() {
        // ---- 03-05：夜间 50，跑步段 120~152，白天 55
        for (int minute = 0; minute <= 360; minute += 10) {
            sample(at("2026-03-05", LocalTime.of(0, 0).plusMinutes(minute)), 50);
        }
        for (int i = 0; i <= 30; i++) {
            LocalTime time = LocalTime.of(7, 0).plusMinutes(i);
            sample(at("2026-03-05", time), 120 + i % 20);
        }
        // 07:10~07:14 顶到 152，前后各留一个断层，让「持续时长」可数
        int[] climb = {148, 150, 152, 151, 149};
        for (int i = 0; i < climb.length; i++) {
            sample(at("2026-03-05", LocalTime.of(7, 10).plusMinutes(i)), climb[i]);
        }
        for (int minute = 480; minute <= 1380; minute += 10) {
            sample(at("2026-03-05", LocalTime.of(0, 0).plusMinutes(minute)), 55);
        }
        jdbc.sql("""
                        INSERT INTO run_activity (id, activity_id, sport, sport_family, run_date,
                            start_time, end_time, start_time_utc, duration_seconds, distance_meters,
                            track_point_count, avg_heart_rate, max_heart_rate, source_file,
                            content_hash, imported_at, source_kind)
                        VALUES ('run-0305', 'run-0305', '户外跑步', 'RUN', '2026-03-05',
                            '2026-03-05T07:00:00', '2026-03-05T07:30:00', '2026-03-04T23:00:00Z',
                            1800, 5000, 900, 140, 152, 'run.json', 'hash-0305', '2026-03-05T08:00:00Z', 'huawei')
                        """)
                .update();

        // ---- 03-01：全天每分钟一条，18:30 一个孤立尖峰
        for (int minute = 0; minute < 24 * 60; minute++) {
            LocalTime time = LocalTime.of(0, 0).plusMinutes(minute);
            sample(at("2026-03-01", time), minute == 18 * 60 + 30 ? 136 : 50 + minute % 5);
        }

        // ---- 03-02：只有两段，中间空 150 分钟
        for (int minute = 0; minute <= 30; minute++) {
            sample(at("2026-03-02", LocalTime.of(0, 0).plusMinutes(minute)), 48);
        }
        for (int minute = 180; minute <= 190; minute++) {
            sample(at("2026-03-02", LocalTime.of(0, 0).plusMinutes(minute)), 48);
        }
    }

    private static String at(String date, LocalTime time) {
        return date + "T" + String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private void sample(String startTime, int bpm) {
        // 07:10~07:14 会被跑步段的循环先插一遍、climb 再覆盖一次，用 REPLACE 免得撞主键
        jdbc.sql("""
                        INSERT OR REPLACE INTO health_metric (record_id, metric_key, start_time, end_time,
                            type_code, value_num, value_text, unit, device_code, time_zone, source_file)
                        VALUES (?, 'DYNAMIC_HEART_RATE', ?, ?, 7, ?, ?, '0',
                            '2374625270', '+0800', 'test.json')
                        """)
                .param(startTime + "-hr")
                .param(startTime)
                .param(startTime)
                .param((double) bpm)
                .param(String.valueOf((double) bpm))
                .update();
    }

    @Test
    void defaultsToLatestDayAndFiveMinuteBuckets() {
        Map<String, Object> result = tools.getHeartRateSeries(null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("2026-03-05", result.get("date"));
        assertEquals("5min", result.get("resolution"));
        assertEquals(5, result.get("bucketMinutes"));

        HeartRateDayStats stats = (HeartRateDayStats) result.get("stats");
        // 37 + 31 + 91，其中 5 分钟跑步段那 5 个点覆盖掉了原来 07:10 的整十分钟点
        assertEquals(159, stats.samples());
        assertEquals(50, stats.min());
        assertEquals(152, stats.max());
        assertEquals("00:00", stats.firstSample());
        assertEquals("23:00", stats.lastSample());
        assertEquals(10, stats.medianGapMinutes(), "大部分时间是 10 分钟一条，跑步段才每分钟");
        assertEquals(1380, stats.spanMinutes(), "00:00 到 23:00");
    }

    @Test
    void bucketsUseLeftClosedWindowsAndKeepMinAvgMax() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-05", null);
        List<HeartRateBucket> buckets = buckets(result);

        HeartRateBucket running = buckets.stream()
                .filter(b -> "07:10".equals(b.time()))
                .findFirst().orElseThrow();
        assertEquals(5, running.samples(), "07:10 起 5 分钟内的 148/150/152/151/149 全在这个桶");
        assertEquals(148, running.min());
        assertEquals(152, running.max());
        assertEquals(150.0, running.average(), 1e-9);
    }

    @Test
    void peakInsideActivityIsExplainedAsWorkout() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-05", null);

        HeartRateExtreme peak = (HeartRateExtreme) result.get("peak");
        assertEquals(152, peak.bpm());
        assertEquals("07:12", peak.time());
        assertEquals(5, peak.sustainedMinutes(), "07:10~07:14 连续在 147 以上");

        @SuppressWarnings("unchecked")
        List<HeartRateActivity> activities = (List<HeartRateActivity>) result.get("activities");
        assertNotNull(activities);
        assertEquals(1, activities.size());
        HeartRateActivity activity = activities.get(0);
        assertEquals("户外跑步", activity.sport());
        assertEquals("07:00", activity.start());
        assertEquals("07:30", activity.end());
        assertEquals(5.0, activity.distanceKm(), 1e-9);

        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("属于运动峰值")), String.valueOf(notes));
        assertFalse(notes.stream().anyMatch(n -> n.contains("传感器噪声")),
                "峰值落在运动时段里，就不能再说它像噪声：" + notes);
    }

    @Test
    void isolatedSpikeIsNotTreatedAsRealPeak() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-01", null);

        HeartRateExtreme peak = (HeartRateExtreme) result.get("peak");
        assertEquals(136, peak.bpm());
        assertEquals("18:30", peak.time());
        assertEquals(1, peak.sustainedMinutes(), "前后都是 50~54，撑不起这个 136");

        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("传感器噪声")), String.valueOf(notes));
        assertNull(result.get("activities"), "当天没有运动就不输出这个字段");
    }

    @Test
    void denseDaySaysMinuteResolution() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-01", null);

        HeartRateDayStats stats = (HeartRateDayStats) result.get("stats");
        assertEquals(1440, stats.samples());
        assertEquals(1, stats.medianGapMinutes());
        assertTrue(notes(result).stream().anyMatch(n -> n.contains("按分钟采样")));
        assertEquals(288, buckets(result).size(), "1440 分钟按 5 分钟切正好 288 个桶");
    }

    @Test
    void sparseAndShortCoverageAreCalledOut() {
        Map<String, Object> sparse = tools.getHeartRateSeries("2026-03-05", null);
        assertTrue(notes(sparse).stream().anyMatch(n -> n.contains("稀疏采样")),
                "10 分钟一条要说明是采样策略，不是没戴表：" + notes(sparse));

        Map<String, Object> shortDay = tools.getHeartRateSeries("2026-03-02", null);
        List<String> notes = notes(shortDay);
        assertTrue(notes.stream().anyMatch(n -> n.contains("只覆盖")), String.valueOf(notes));
        assertTrue(notes.stream().anyMatch(n -> n.contains("150 分钟没有任何采样")), String.valueOf(notes));
    }

    @Test
    void minuteResolutionKeepsEverySample() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-01", "minute");
        List<HeartRateBucket> buckets = buckets(result);
        assertEquals(1440, buckets.size());
        assertTrue(buckets.stream().allMatch(b -> b.samples() == 1));
    }

    @Test
    void hourResolutionCollapsesTo24Buckets() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-01", "hour");
        assertEquals(24, buckets(result).size());
        assertEquals(60, result.get("bucketMinutes"));
    }

    @Test
    void invalidResolutionIsRejected() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-03-05", "10min");

        assertEquals("INVALID_ARGUMENT", result.get("status"));
        assertEquals("resolution", result.get("param"));
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) result.get("allowedValues");
        assertEquals(HeartRateSeriesService.resolutions(), allowed);
    }

    @Test
    void invalidDateIsRejected() {
        Map<String, Object> result = tools.getHeartRateSeries("2026-13-40", null);

        assertEquals("INVALID_ARGUMENT", result.get("status"));
        assertEquals("date", result.get("param"));
    }

    @Test
    void missingDayReturnsCoveredRange() {
        Map<String, Object> result = tools.getHeartRateSeries("2020-01-01", null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-03-01 ~ 2026-03-05", result.get("coveredRange"));
    }

    @SuppressWarnings("unchecked")
    private static List<HeartRateBucket> buckets(Map<String, Object> result) {
        return (List<HeartRateBucket>) result.get("buckets");
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> result) {
        return (List<String>) result.get("notes");
    }
}
