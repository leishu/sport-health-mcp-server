package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.SleepDetailCoverage;
import org.example.sporthealth.domain.SleepDetailNight;
import org.example.sporthealth.domain.SleepFragments;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepStageSegment;
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
 * {@code get_sleep_detail} 的口径：时间线怎么合、碎片二进制怎么解、三份来源缺失时怎么表达。
 *
 * <p>单独一套种子数据（不与 {@code SleepHealthToolsTest} 共用）：那边的夜晚是给汇总用的合成数据，
 * 分期分钟数和会话对不上；详情的断言需要「分期合计 = 会话合计」这种自洽的一夜，
 * 否则测试只能证明代码跑通，证明不了算法对。
 *
 * <p>用真实 SQLite：碎片解码、{@code metric_key IN (...)} 的窗口过滤、
 * 两种时间格式的跨表比较都是数据库侧行为，mock 测不出来。
 */
class SleepDetailToolsTest {

    /** 设备时区，与库里 {@code time_zone} 的唯一取值一致。 */
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

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
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        tools = new SleepHealthTools(new SleepAnalyticsService(store, new DateRangeResolver(clock)));
    }

    /**
     * 两晚：
     * <ul>
     *   <li>07-10：分期（19 段，含一对相邻浅睡用来验合并）+ 报告 + 两块碎片 + 前一天午睡；</li>
     *   <li>07-11：只有会话，没有分期/报告/碎片，用来验「缺数据是 null 而不是 0」。</li>
     * </ul>
     */
    private void seed() {
        // 07-09 22:12 → 07-10 05:05，413 分钟卧床 = 374 睡着 + 24 潜伏期 + 15 夜间清醒
        session("2026-07-09 22:12:00", "2026-07-10 05:05:00", "2026-07-10", 413, 374, 108, 188, 78, 15);
        session("2026-07-10 23:00:00", "2026-07-11 06:00:00", "2026-07-11", 420, 400, 90, 250, 60, 20);

        // 逐分钟分期：合计深 108 / 浅 188 / 快动 78 / 清醒 39，与上面一行会话自洽
        stage("PROFESSIONAL_SLEEP_WAKE", "2026-07-09T22:12", "2026-07-09T22:36");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-09T22:36", "2026-07-09T22:40");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-09T22:40", "2026-07-09T22:45");
        stage("PROFESSIONAL_SLEEP_DEEP", "2026-07-09T22:45", "2026-07-09T23:22");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-09T23:22", "2026-07-09T23:56");
        stage("PROFESSIONAL_SLEEP_DEEP", "2026-07-09T23:56", "2026-07-10T00:26");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T00:26", "2026-07-10T00:59");
        stage("PROFESSIONAL_SLEEP_DREAM", "2026-07-10T00:59", "2026-07-10T01:11");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T01:11", "2026-07-10T01:22");
        stage("PROFESSIONAL_SLEEP_DREAM", "2026-07-10T01:22", "2026-07-10T01:29");
        stage("PROFESSIONAL_SLEEP_WAKE", "2026-07-10T01:29", "2026-07-10T01:44");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T01:44", "2026-07-10T01:59");
        stage("PROFESSIONAL_SLEEP_DEEP", "2026-07-10T01:59", "2026-07-10T02:12");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T02:12", "2026-07-10T02:26");
        stage("PROFESSIONAL_SLEEP_DREAM", "2026-07-10T02:26", "2026-07-10T02:40");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T02:40", "2026-07-10T02:49");
        stage("PROFESSIONAL_SLEEP_DREAM", "2026-07-10T02:49", "2026-07-10T03:34");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T03:34", "2026-07-10T03:58");
        stage("PROFESSIONAL_SLEEP_DEEP", "2026-07-10T03:58", "2026-07-10T04:26");
        stage("PROFESSIONAL_SLEEP_SHALLOW", "2026-07-10T04:26", "2026-07-10T05:05");

        // 入睡前的午睡：三段首尾相接，合并后应该只有一段 33 分钟
        stage("PROFESSIONAL_SLEEP_NOON", "2026-07-09T12:02", "2026-07-09T12:12");
        stage("PROFESSIONAL_SLEEP_NOON", "2026-07-09T12:12", "2026-07-09T12:25");
        stage("PROFESSIONAL_SLEEP_NOON", "2026-07-09T12:25", "2026-07-09T12:35");
        // 混进来的「午睡」起点在 01:00，是夜睡被标错了，必须被时段筛选挡掉
        stage("PROFESSIONAL_SLEEP_NOON", "2026-07-09T01:00", "2026-07-09T01:30");

        report("2026-07-09T22:12", "2026-07-10T05:05", "2026-07-10",
                80d, 91d, 24d, 54d, -1d, 46d, 15d, 1d,
                epoch("2026-07-09T22:12"), epoch("2026-07-09T22:36"), epoch("2026-07-10T05:05"));

        // 两块碎片：22:36–01:29、01:42–05:05，中间的 13 分钟就是设备口径的清醒
        fragment("2026-07-10T01:32:30", LocalDateTime.of(2026, 7, 9, 22, 36),
                LocalDateTime.of(2026, 7, 10, 1, 29));
        fragment("2026-07-10T05:05:47", LocalDateTime.of(2026, 7, 10, 1, 42),
                LocalDateTime.of(2026, 7, 10, 5, 5));
        // 同 type 但不是睡眠碎片的块：块名对不上，解码必须放弃而不是硬读时间戳
        blob("2026-07-11T03:00:00", "other_blob");
    }

    @Test
    void detailExposesTimelineWakeEpisodesAndFragments() {
        Map<String, Object> result = tools.getSleepDetail("2026-07-10", null, null, null);
        assertEquals("OK", result.get("status"));
        assertEquals("2026-07-10 ~ 2026-07-10", result.get("range"));

        SleepDetailNight night = nights(result).get(0);
        assertEquals("2026-07-09 22:12:00", night.bedtime());
        assertEquals("2026-07-10 05:05:00", night.waketime());
        assertEquals(413, night.timeInBedMinutes());
        assertEquals(374, night.asleepMinutes());
        assertEquals(15, night.awakeMinutes());
        assertEquals(1, night.wakeCount().intValue(), "只有 01:29–01:44 那 15 分钟算一次醒来");
        assertEquals(24, night.sleepLatencyMinutes().intValue());

        // 时间线：20 段原始分期，第一对相邻浅睡合并后是 19 段
        assertEquals(19, night.timelineSegments());
        assertEquals(19, night.timeline().size());
        assertFalse(night.timelineTruncated());
        SleepStageSegment first = night.timeline().get(0);
        assertEquals("wake", first.stage());
        assertEquals("2026-07-09 22:12:00", first.start());
        assertEquals(24, first.minutes(), "入睡前那段清醒在时间线里，但不算 WASO 也不算醒来");
        SleepStageSegment second = night.timeline().get(1);
        assertEquals("light", second.stage());
        assertEquals(9, second.minutes(), "相邻两段浅睡（4 + 5）应合并成 9 分钟");
        assertEquals("2026-07-09 22:45:00", second.end(), "4 分钟 + 5 分钟合成一段，终点是后一段的结束");

        // 阶段合计取自分期，与会话数字自洽
        assertEquals(108, night.stages().deepMinutes().intValue());
        assertEquals(188, night.stages().lightMinutes().intValue());
        assertEquals(78, night.stages().remMinutes().intValue());
        assertEquals(39, night.stages().wakeMinutes().intValue(), "含入睡前 24 分钟");
        assertEquals(28.9, night.stages().deepPercentOfSleep(), 1e-9, "108/374");

        // 醒来时刻
        assertEquals(1, night.wakeEpisodes().size());
        SleepStageSegment wake = night.wakeEpisodes().get(0);
        assertEquals("2026-07-10 01:29:00", wake.start());
        assertEquals("2026-07-10 01:44:00", wake.end());
        assertEquals(15, wake.minutes());

        // 碎片：两块 + 一个 13 分钟的间隙
        SleepFragments fragments = night.fragments();
        assertTrue(fragments.available());
        assertEquals(2, fragments.count().intValue());
        assertEquals(376, fragments.totalMinutes().intValue());
        assertEquals(203, fragments.longestMinutes().intValue());
        assertEquals(188d, fragments.averageMinutes(), 1e-9);
        assertEquals("2026-07-09 22:36:00", fragments.items().get(0).start());
        assertEquals(173, fragments.items().get(0).minutes());
        assertEquals("2026-07-10 05:05:00", fragments.items().get(1).end());
        assertEquals(1, fragments.gaps().size());
        assertEquals(13, fragments.gaps().get(0).minutes());
        assertEquals(13, fragments.gapMinutes().intValue());
        assertEquals(1, fragments.wakeGapCount().intValue());
        assertNull(fragments.note());

        // 报告补充指标
        assertNotNull(night.report());
        assertEquals(46d, night.report().avgHeartRateBpm(), 1e-9);
        assertEquals(15d, night.report().avgBreathRatePerMin(), 1e-9);
        assertEquals("2026-07-09 22:36:00", night.report().fallAsleepTime());
        assertEquals("2026-07-10 05:05:00", night.report().risingTime());
        assertNull(night.spo2Percent(), "血氧 -1 是哨兵值");

        // 午睡：三段合并成一段，01:00 那一段被时段筛选挡掉
        assertNotNull(night.daytimeNap());
        assertEquals(33, night.daytimeNap().minutes());
        assertEquals(1, night.daytimeNap().segments().size());
        assertEquals("2026-07-09 12:02:00", night.daytimeNap().segments().get(0).start());

        assertEquals(List.of("stages", "report", "fragments"), night.sources());
        assertEquals(List.of("fragments.items 是设备认定的连续睡眠块，fragments.gaps 是块之间的间隙；"
                        + "它与 wakeEpisodes（分期口径，连续清醒 ≥2 分钟才算一次）可能差 1~2 分钟，"
                        + "两者都给出、不取平均。"),
                result.get("notes"));
    }

    /** 详情与汇总必须是同一套数字，否则两个工具给出的答案会互相打脸。 */
    @Test
    void detailAgreesWithSummaryForSameNight() {
        SleepDetailNight detail = nights(tools.getSleepDetail("2026-07-10", null, null, null)).get(0);
        SleepNight summary = summaryNights(tools.getSleepSummary("2026-07-10", "2026-07-10", "day")).get(0);

        assertEquals(summary.timeInBedMinutes(), detail.timeInBedMinutes());
        assertEquals(summary.asleepMinutes(), detail.asleepMinutes());
        assertEquals(summary.awakeMinutes(), detail.awakeMinutes());
        assertEquals(summary.wakeCount(), detail.wakeCount());
        assertEquals(summary.score(), detail.score());
        assertEquals(summary.efficiencyPercent(), detail.efficiencyPercent());
        assertEquals(summary.sleepLatencyMinutes(), detail.sleepLatencyMinutes());
    }

    /** 只有会话、没有分期/报告/碎片的夜晚：缺数据一律 null，不能变成 0。 */
    @Test
    void absentSourcesAreNullNotZero() {
        SleepDetailNight night = nights(tools.getSleepDetail("2026-07-11", null, null, null)).get(0);

        assertEquals(400, night.asleepMinutes(), "会话自己的时长仍然可用");
        assertNull(night.stages(), "没有分期段时整块是 null，而不是四个 0");
        assertEquals(0, night.timelineSegments());
        assertTrue(night.timeline().isEmpty());
        assertTrue(night.wakeEpisodes().isEmpty());
        assertNull(night.report(), "没有报告时 report 块是 null");
        assertNull(night.score());
        assertNull(night.hrvMs());

        SleepFragments fragments = night.fragments();
        assertFalse(fragments.available());
        assertNull(fragments.count());
        assertNull(fragments.longestMinutes());
        assertNull(fragments.gapMinutes());
        assertTrue(fragments.items().isEmpty());
        assertTrue(fragments.gaps().isEmpty());
        assertNotNull(fragments.note(), "要明确写出「这段时间还没有碎片数据」");
        assertNull(night.daytimeNap());
        assertEquals(List.of("stages"), night.sources());
    }

    @Test
    void includeTimelineFalseKeepsSegmentCount() {
        Map<String, Object> result = tools.getSleepDetail("2026-07-10", null, null, Boolean.FALSE);
        SleepDetailNight night = nights(result).get(0);

        assertTrue(night.timeline().isEmpty());
        assertEquals(19, night.timelineSegments(), "关掉时间线不能连段数一起丢掉");
        assertEquals(108, night.stages().deepMinutes().intValue());
        assertTrue(String.valueOf(result.get("notes")).contains("includeTimeline=false"));
    }

    @Test
    void rangeModeReturnsEveryNightWithCoverage() {
        Map<String, Object> result = tools.getSleepDetail(null, "2026-07-10", "2026-07-11", null);

        List<SleepDetailNight> nights = nights(result);
        assertEquals(2, nights.size());
        assertEquals(2, result.get("nightCount"));
        assertEquals("2026-07-10", nights.get(0).date().toString());
        assertEquals("2026-07-11", nights.get(1).date().toString());

        SleepDetailCoverage coverage = (SleepDetailCoverage) result.get("coverage");
        assertEquals(2, coverage.nights());
        assertEquals(1, coverage.stageNights());
        assertEquals(1, coverage.reportNights());
        assertEquals(1, coverage.fragmentNights());
        assertNotNull(coverage.note(), "两夜都缺一半来源，必须给出说明");
    }

    /** 不传任何参数：取最新一夜，而不是整段历史。 */
    @Test
    void defaultsToLatestNight() {
        Map<String, Object> result = tools.getSleepDetail(null, null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("2026-07-11 ~ 2026-07-11", result.get("range"));
    }

    @Test
    void rejectsInvalidArguments() {
        assertInvalid(tools.getSleepDetail("2026-07-10", "2026-07-01", null, null), "date");
        assertInvalid(tools.getSleepDetail("2026-13-40", null, null, null), "date");
        assertInvalid(tools.getSleepDetail(null, "2026-07-11", "2026-07-10", null), "startDate");
        // 超过 31 夜：详情不是给长区间用的
        assertInvalid(tools.getSleepDetail(null, "2026-01-01", "2026-07-10", null), "endDate");
    }

    @Test
    void returnsNoDataOutsideCoveredRange() {
        Map<String, Object> result = tools.getSleepDetail("2020-01-01", null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals("2026-07-10 ~ 2026-07-11", result.get("coveredRange"));
    }

    private static void assertInvalid(Map<String, Object> result, String param) {
        assertEquals("INVALID_ARGUMENT", result.get("status"), String.valueOf(result));
        assertEquals(param, result.get("param"));
        assertNotNull(result.get("message"));
    }

    @SuppressWarnings("unchecked")
    private static List<SleepDetailNight> nights(Map<String, Object> result) {
        return (List<SleepDetailNight>) result.get("nights");
    }

    @SuppressWarnings("unchecked")
    private static List<SleepNight> summaryNights(Map<String, Object> result) {
        return (List<SleepNight>) result.get("nights");
    }

    private void session(String start, String end, String day, int inBed, int asleep,
                         int deep, int light, int rem, int awake) {
        jdbc.sql("""
                INSERT INTO sleep_session (session_start, session_end, sleep_date, sleep_type,
                       in_bed_minutes, asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                       awake_minutes, noon_minutes, segment_count)
                VALUES (:start, :end, :day, 'NIGHT', :inBed, :asleep, :deep, :light, :rem, :awake, 0, 1)
                """)
                .param("start", start).param("end", end).param("day", day)
                .param("inBed", inBed).param("asleep", asleep).param("deep", deep)
                .param("light", light).param("rem", rem).param("awake", awake)
                .update();
    }

    private void report(String start, String end, String day, Double score, Double efficiency,
                        Double latency, Double hrv, Double spo2, Double heartRate, Double breathRate,
                        Double snore, long bedTime, long fallAsleep, long rising) {
        jdbc.sql("""
                INSERT INTO sleep_report (record_id, start_time, end_time, sleep_date, sleep_score,
                       sleep_efficiency, sleep_latency, avg_hrv, avg_oxygen_saturation, deep_part,
                       wake_count, turn_over_count, fall_asleep_time, rising_time, bed_time,
                       avg_heartrate, avg_breathrate, snore_freq)
                VALUES (:id, :start, :end, :day, :score, :eff, :latency, :hrv, :spo2, 100,
                        0, 0, :fallAsleep, :rising, :bedTime, :hr, :br, :snore)
                """)
                .param("id", start + day).param("start", start).param("end", end).param("day", day)
                .param("score", score).param("eff", efficiency).param("latency", latency)
                .param("hrv", hrv).param("spo2", spo2)
                .param("fallAsleep", fallAsleep).param("rising", rising).param("bedTime", bedTime)
                .param("hr", heartRate).param("br", breathRate).param("snore", snore)
                .update();
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

    /** 写一条 type=700008 的睡眠碎片：detail_data 是华为的私有二进制，这里按实测布局拼出来。 */
    private void fragment(String recordTime, LocalDateTime start, LocalDateTime end) {
        byte[] raw = new byte[72];
        putBigEndian(raw, 0, 1);
        putBigEndian(raw, 4, 14);
        byte[] name = "sleep_fragment".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, raw, 8, name.length);
        putLittleEndian(raw, raw.length - 16, start.toEpochSecond(ZONE));
        putLittleEndian(raw, raw.length - 12, end.toEpochSecond(ZONE));
        insertBlob(recordTime, HexFormat.of().formatHex(raw));
    }

    /** 块名不是 sleep_fragment 的二进制块：必须被解码器拒掉。 */
    private void blob(String recordTime, String name) {
        byte[] raw = new byte[72];
        putBigEndian(raw, 0, 1);
        putBigEndian(raw, 4, name.length());
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8), 0, raw, 8, name.length());
        putLittleEndian(raw, raw.length - 16, 1783000000L);
        putLittleEndian(raw, raw.length - 12, 1783010000L);
        insertBlob(recordTime, HexFormat.of().formatHex(raw));
    }

    private void insertBlob(String recordTime, String hex) {
        jdbc.sql("""
                INSERT INTO sample_sequence (record_id, type_code, data_id, sub_type, start_time,
                       end_time, time_zone, device_code, summary_data, detail_data, source_file)
                VALUES (:id, 700008, 1, 0, :start, :start, '+0800', 'dev', '', :detail, 'test.json')
                """)
                .param("id", "frag-" + recordTime).param("start", recordTime).param("detail", hex)
                .update();
    }

    private static long epoch(String localDateTime) {
        return LocalDateTime.parse(localDateTime).toEpochSecond(ZONE);
    }

    private static void putBigEndian(byte[] raw, int offset, int value) {
        raw[offset] = (byte) ((value >>> 24) & 0xFF);
        raw[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        raw[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        raw[offset + 3] = (byte) (value & 0xFF);
    }

    private static void putLittleEndian(byte[] raw, int offset, long value) {
        for (int i = 0; i < 4; i++) {
            raw[offset + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
    }
}
