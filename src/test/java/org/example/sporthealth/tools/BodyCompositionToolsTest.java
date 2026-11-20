package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.BodyMeasurement;
import org.example.sporthealth.domain.BodyMetricTrend;
import org.example.sporthealth.domain.BodyMonthSummary;
import org.example.sporthealth.domain.BodyProfile;
import org.example.sporthealth.repository.BodyCompositionStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.service.BodyCompositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code get_body_composition} 的口径：一台秤多个人时，统计不能跨人合并。
 *
 * <p>用真实 SQLite 而不是 mock：这里守的核心是 SQL 的分组与过滤
 * （{@code GROUP BY ROUND(height_cm), gender}、{@code ROUND(height_cm) = :height}），
 * mock 掉仓储就等于没测。
 */
class BodyCompositionToolsTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private BodyCompositionTools tools;

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
        tools = new BodyCompositionTools(new BodyCompositionService(new BodyCompositionStore(jdbc)));
    }

    /**
     * 三套配置，体重区间刻意互相远离，用来证明「不跨人合并」：
     * <ul>
     *   <li>{@code 186/男}（本人）：8 条，70.0 → 72.0 kg，全部带体成分，
     *       其中 2026-02-18 称了两次；</li>
     *   <li>{@code 168/女}：4 条，60.0 → 58.0 kg，**只有体重、没有体成分**；</li>
     *   <li>{@code 168/性别未知}：2 条，59.0 kg —— 与上面同身高但性别为 NULL，
     *       用来验证「同身高多组」的提示；</li>
     *   <li>{@code 193/男}：1 条 85.0 kg —— 把全表极值拉到 85.0，任何跨人统计都会暴露。</li>
     * </ul>
     */
    private void seed() {
        add("2025-12-05T06:00", 186, 1, 70.0, 20.0, 30.0);
        add("2025-12-20T06:10", 186, 1, 70.4, 20.2, 30.1);
        add("2026-01-04T06:00", 186, 1, 70.8, 20.4, 30.2);
        add("2026-01-19T06:05", 186, 1, 71.2, 20.6, 30.3);
        add("2026-02-03T06:00", 186, 1, 71.6, 20.8, 30.4);
        add("2026-02-18T06:00", 186, 1, 71.9, 21.0, 30.5);
        add("2026-02-18T06:05", 186, 1, 72.1, 21.1, 30.55);
        add("2026-03-01T06:00", 186, 1, 72.0, 21.2, 30.6);

        add("2025-12-12T07:00", 168, 0, 60.0, null, null);
        add("2026-01-10T07:00", 168, 0, 59.4, null, null);
        add("2026-02-10T07:00", 168, 0, 58.6, null, null);
        add("2026-03-10T07:00", 168, 0, 58.0, null, null);

        add("2025-12-12T07:00", 168, null, 59.0, null, null);
        add("2026-01-12T07:00", 168, null, 59.0, null, null);

        add("2026-02-20T07:00", 193, 1, 85.0, null, null);
    }

    private void add(String measuredAt, double height, Integer gender, double weight,
                     Double fatPercent, Double muscle) {
        jdbc.sql("""
                        INSERT INTO body_measurement (record_id, measured_at, weight_kg, bmi,
                            body_fat_percent, body_fat_kg, muscle_mass_kg, protein_percent,
                            body_water_percent, bone_salt_kg, visceral_fat_level, waist_hip_ratio,
                            basal_metabolism_kcal, body_age, body_score, height_cm, heart_rate,
                            gender, has_body_composition, source_file)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test.json')
                        """)
                .param(measuredAt + "-" + (int) height + "-" + gender)
                .param(measuredAt)
                .param(weight)
                .param(fatPercent == null ? null : 21.0)
                .param(fatPercent)
                .param(fatPercent == null ? null : weight * fatPercent / 100d)
                .param(muscle)
                .param(fatPercent == null ? null : 13.5)
                .param(fatPercent == null ? null : 67.0)
                .param(fatPercent == null ? null : 3.3)
                .param(fatPercent == null ? null : 7.0)
                .param(fatPercent == null ? null : 0.85)
                .param(fatPercent == null ? null : 1800.0)
                .param(fatPercent == null ? null : 42)
                .param(fatPercent == null ? null : 86.0)
                .param(height)
                .param(fatPercent == null ? null : 55)
                .param(gender)
                .param(fatPercent == null ? 0 : 1)
                .update();
    }

    @Test
    void withoutFiltersReturnsConfigsOnly() {
        Map<String, Object> result = tools.getBodyComposition(null, null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("configs", result.get("mode"));
        List<BodyProfile> configs = configs(result);
        assertEquals(4, configs.size(), "四套配置要各自成行");
        assertEquals(15, result.get("measurementCount"));

        // 条数最多的排最前，但不能被说成「就是本人」
        assertEquals("186cm · 男", configs.get(0).label());
        assertEquals(8, configs.get(0).measurements());
        assertEquals(8, configs.get(0).bodyComposition());

        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("不能合并统计")), String.valueOf(notes));
        assertTrue(notes.stream().anyMatch(n -> n.contains("请再加 gender + heightCm")),
                String.valueOf(notes));
        assertFalse(notes.stream().anyMatch(n -> n.contains("通常就是本人")),
                "不能把条数最多的一套断言成本人：" + notes);
    }

    @Test
    void genderOnlyStillReturnsConfigs() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "male", null);

        assertEquals("configs", result.get("mode"), "只给 gender 不该进明细模式");
        List<BodyProfile> configs = configs(result);
        assertEquals(2, configs.size(), "186/男 与 193/男");
        assertTrue(configs.stream().allMatch(profile -> "male".equals(profile.gender())));
    }

    @Test
    void heightOnlyListsBothGenderGroupsAndWarns() {
        Map<String, Object> result = tools.getBodyComposition(null, null, null, 168);

        assertEquals("configs", result.get("mode"));
        List<BodyProfile> configs = configs(result);
        assertEquals(2, configs.size(), "168cm 下有「女」与「性别未知」两组");
        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("很可能是同一个人")), String.valueOf(notes));
        assertTrue(notes.stream().anyMatch(n -> n.contains("当前没传 gender")), String.valueOf(notes));
    }

    @Test
    void fullFilterReturnsOnlyThatPersonsMeasurements() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "male", 186);

        assertEquals("OK", result.get("status"));
        assertEquals("measurements", result.get("mode"));
        assertEquals(8, result.get("count"));

        BodyProfile profile = (BodyProfile) result.get("profile");
        assertEquals("186cm · 男", profile.label());
        assertEquals(8, profile.measurements());
        assertEquals(8, profile.bodyComposition());

        List<BodyMeasurement> items = measurements(result);
        assertEquals(8, items.size());
        assertTrue(items.stream().allMatch(m -> m.weightKg() >= 70.0 && m.weightKg() <= 72.1),
                "明细里不能出现别人的体重");
    }

    @Test
    void trendsNeverCrossPeople() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "male", 186);
        Map<String, BodyMetricTrend> trends = trends(result);

        BodyMetricTrend weight = trends.get("weight");
        assertNotNull(weight);
        // 全表是 58.0~85.0：这里必须只看到 70.0~72.1
        assertEquals(70.0, weight.first(), 1e-9);
        assertEquals(72.0, weight.last(), 1e-9, "最后一条是 03-01 的 72.0，不是 02-18 的 72.1");
        assertEquals(2.0, weight.change(), 1e-9);
        assertEquals(2.9, weight.changePercent(), 0.05);
        assertEquals(70.0, weight.min(), 1e-9);
        assertEquals(72.1, weight.max(), 1e-9, "最大值仍要取到 02-18 那次");
        assertEquals(8, weight.count());

        BodyMetricTrend fat = trends.get("bodyFatPercent");
        assertEquals(20.0, fat.first(), 1e-9);
        assertEquals(21.2, fat.last(), 1e-9);
        assertEquals(1.2, fat.change(), 1e-9);

        assertEquals(8, trends.get("bmi").count());
        assertEquals(8, trends.get("muscleMassKg").count());
    }

    @Test
    void byMonthKeepsSameDayDuplicatesButCountsDays() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "male", 186);
        List<BodyMonthSummary> months = months(result);

        assertEquals(4, months.size(), "2025-12 ~ 2026-03");
        BodyMonthSummary february = months.stream()
                .filter(m -> "2026-02".equals(m.month())).findFirst().orElseThrow();
        assertEquals(3, february.measurements(), "02-03、02-18 两次");
        assertEquals(2, february.days());
        assertEquals(71.867, february.weightAverage(), 0.01);
    }

    @Test
    void weightOnlyProfileHasNoCompositionTrends() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "female", 168);

        assertEquals("measurements", result.get("mode"));
        assertEquals(4, result.get("count"));

        Map<String, BodyMetricTrend> trends = trends(result);
        assertEquals(1, trends.size(), "只有体重，其它指标一个都不该出现：" + trends.keySet());
        assertNotNull(trends.get("weight"));
        assertNull(trends.get("bodyFatPercent"));

        List<String> notes = notes(result);
        assertTrue(notes.stream().anyMatch(n -> n.contains("都只称了体重")), String.valueOf(notes));
    }

    @Test
    void defaultRangeAnchorsOnThatProfilesLastWeighIn() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "male", 186);

        // 该配置最后一条是 2026-03-01，往前 90 天 = 2025-12-03
        assertEquals("2025-12-02 ~ 2026-03-01", result.get("range"));
        assertEquals(8, result.get("count"), "默认窗口要把 12-05 那条也包进来");
    }

    @Test
    void configsDefaultRangeCoversWholeHistory() {
        Map<String, Object> result = tools.getBodyComposition(null, null, null, null);

        // 配置发现必须看全库：只开 90 天会漏掉很久没上秤的那套
        assertEquals("2025-12-05 ~ 2026-03-10", result.get("range"));
    }

    @Test
    void nonexistentProfileReturnsAvailableConfigs() {
        Map<String, Object> result = tools.getBodyComposition(null, null, "female", 186);

        assertEquals("NO_DATA", result.get("status"));
        @SuppressWarnings("unchecked")
        List<BodyProfile> available = (List<BodyProfile>) result.get("availableConfigs");
        assertEquals(4, available.size(), "报错时要把库里真实存在的配置一起给出来");
        assertNotNull(result.get("hint"));
    }

    @Test
    void invalidGenderAndHeightAreRejected() {
        Map<String, Object> badGender = tools.getBodyComposition(null, null, "xx", 186);
        assertEquals("INVALID_ARGUMENT", badGender.get("status"));
        assertEquals("gender", badGender.get("param"));

        Map<String, Object> badHeight = tools.getBodyComposition(null, null, "male", 18);
        assertEquals("INVALID_ARGUMENT", badHeight.get("status"));
        assertEquals("heightCm", badHeight.get("param"));
    }

    @Test
    void chineseGenderAndAliasesAreAccepted() {
        assertEquals("measurements", tools.getBodyComposition(null, null, "男", 186).get("mode"));
        assertEquals("measurements", tools.getBodyComposition(null, null, "M", 186).get("mode"));
    }

    @Test
    void longRangeAndReversedDates() {
        // 193/男 只有 1 条，跨六年也不该触发条数上限
        Map<String, Object> ok = tools.getBodyComposition("2020-01-01", "2026-12-31", "male", 193);
        assertEquals("OK", ok.get("status"));
        assertEquals(1, ok.get("count"));

        Map<String, Object> bad = tools.getBodyComposition("2026-03-02", "2026-03-01", "male", 186);
        assertEquals("INVALID_ARGUMENT", bad.get("status"));
        assertEquals("startDate", bad.get("param"));
    }

    @SuppressWarnings("unchecked")
    private static List<BodyProfile> configs(Map<String, Object> result) {
        return (List<BodyProfile>) result.get("configs");
    }

    @SuppressWarnings("unchecked")
    private static List<BodyMeasurement> measurements(Map<String, Object> result) {
        return (List<BodyMeasurement>) result.get("measurements");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BodyMetricTrend> trends(Map<String, Object> result) {
        return (Map<String, BodyMetricTrend>) result.get("trends");
    }

    @SuppressWarnings("unchecked")
    private static List<BodyMonthSummary> months(Map<String, Object> result) {
        return (List<BodyMonthSummary>) result.get("byMonth");
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> result) {
        return (List<String>) result.get("notes");
    }
}
