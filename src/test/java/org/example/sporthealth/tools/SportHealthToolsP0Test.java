package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RacePrediction;
import org.example.sporthealth.domain.RunSplit;
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.domain.TrainingMix;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0 工具层返回结构：比赛预测、训练负荷、训练类型、训练构成与 GAP 字段。
 *
 * <p>用内存仓储，重点验证工具层把分析结果摊平到 {@code Map} 后字段齐全、状态语义正确；
 * 天气影响的正向路径需要 {@code run_weather} 表，放在
 * {@link SportHealthToolsWeatherImpactTest} 里用真实 SQLite 覆盖。
 */
class SportHealthToolsP0Test {

    private SportHealthTools tools;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService analytics = new RunAnalyticsService(repository, new DateRangeResolver(clock));
        tools = new SportHealthTools(analytics, repository, properties, null);
    }

    @Test
    void predictRaceTimeReturnsStandardDistances() {
        Map<String, Object> result = tools.predictRaceTime(null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("riegel", result.get("method"));
        assertEquals(1.06, (Double) result.get("exponent"), 1e-9);
        assertNotNull(result.get("references"));
        List<RacePrediction.Prediction> predictions = predictions(result);
        assertEquals(4, predictions.size(), "默认输出 5K/10K/半马/全马");

        RacePrediction.Prediction fiveK = predictions.get(0);
        assertEquals(5000d, fiveK.targetMeters(), 1e-6);
        assertEquals("ACTUAL", fiveK.source());
        assertNotNull(fiveK.predictedText());
        assertNotNull(fiveK.paceText());

        RacePrediction.Prediction full = predictions.get(3);
        assertEquals("RIEGEL", full.source());
        assertNotNull(full.confidence());
    }

    @Test
    void predictRaceTimeAcceptsCustomDistance() {
        Map<String, Object> result = tools.predictRaceTime(null, null, 12d);

        assertEquals("OK", result.get("status"));
        List<RacePrediction.Prediction> predictions = predictions(result);
        assertEquals(1, predictions.size());
        assertEquals(12000d, predictions.get(0).targetMeters(), 1e-6);
    }

    @Test
    void trainingLoadExposesAcwrFields() {
        Map<String, Object> result = tools.getTrainingLoad("2026-11-03", null);

        assertEquals("OK", result.get("status"));
        assertEquals("distance", result.get("metric"));
        assertEquals("HIGH_RISK", result.get("riskLevel"));
        assertEquals("LOW", result.get("confidence"));
        assertEquals(false, result.get("dataSufficient"));
        assertEquals(10, result.get("chronicCoverageDays"));
        assertTrue((Double) result.get("acwr") > 2.0);
        assertEquals(4, result.get("restDaysLast7"));
        assertEquals(1, result.get("consecutiveTrainingDays"));
        assertNotNull(result.get("monotony"));
        assertNotNull(result.get("strain"));
        assertNotNull(result.get("recommendation"));
        TrainingLoad.LoadWindow acute = (TrainingLoad.LoadWindow) result.get("acute");
        TrainingLoad.LoadWindow chronic = (TrainingLoad.LoadWindow) result.get("chronic");
        assertEquals(3, acute.runCount());
        assertEquals(5, chronic.runCount());
    }

    @Test
    void trainingLoadSupportsDurationMetric() {
        Map<String, Object> result = tools.getTrainingLoad("2026-11-03", "duration");

        assertEquals("OK", result.get("status"));
        assertEquals("duration", result.get("metric"));
        TrainingLoad.LoadWindow acute = (TrainingLoad.LoadWindow) result.get("acute");
        assertTrue(acute.load() > 100d);
    }

    @Test
    void classifyRunIdentifiesLongRun() {
        Map<String, Object> result = tools.classifyRun("20261101户外跑步");

        assertEquals("OK", result.get("status"));
        assertEquals("LONG_RUN", result.get("type"));
        assertEquals("长距离跑", result.get("typeText"));
        assertFalse(((List<?>) result.get("reasons")).isEmpty());
        assertNotNull(result.get("gradeAdjustedPaceSecondsPerKm"));
        assertNotNull(result.get("baselinePaceText"));
    }

    @Test
    void classifyRunReportsMissingId() {
        Map<String, Object> result = tools.classifyRun("不存在的记录");

        assertEquals("NOT_FOUND", result.get("status"));
    }

    @Test
    void trainingMixSummarizesTheWholeDataset() {
        Map<String, Object> result = tools.getTrainingMix(null, null);

        assertEquals("OK", result.get("status"));
        assertEquals(5, result.get("totalRuns"));
        List<TrainingMix.TypeSummary> types = types(result);
        List<TrainingMix.RunTypeRow> runs = runs(result);
        assertEquals(5, runs.size());
        assertEquals(5, types.stream().mapToInt(TrainingMix.TypeSummary::runCount).sum());
        assertTrue(types.stream().anyMatch(type -> "LONG_RUN".equals(type.type())));
        assertNotNull(result.get("text"));
    }

    @Test
    void splitsExposeGradeAdjustedPace() {
        Map<String, Object> result = tools.getRunSplits("20261025户外跑步", 1d);

        assertEquals("OK", result.get("status"));
        assertNotNull(result.get("avgGradeAdjustedPaceSecondsPerKm"));
        assertNotNull(result.get("avgGradeAdjustedPaceText"));
        RunSplit first = ((List<?>) result.get("splits")).stream()
                .map(RunSplit.class::cast)
                .findFirst()
                .orElseThrow();
        assertNotNull(first.netElevationMeters());
        assertNotNull(first.gradePercent());
        assertNotNull(first.gradeAdjustedPaceSecondsPerKm());
        assertNotNull(first.gradeAdjustedPaceText());
    }

    @Test
    void weatherImpactWithoutStoreReportsNoData() {
        Map<String, Object> result = tools.getWeatherImpact(null, null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertNotNull(result.get("message"));
        assertNotNull(result.get("range"));
    }

    @SuppressWarnings("unchecked")
    private static List<RacePrediction.Prediction> predictions(Map<String, Object> result) {
        return (List<RacePrediction.Prediction>) result.get("predictions");
    }

    @SuppressWarnings("unchecked")
    private static List<TrainingMix.TypeSummary> types(Map<String, Object> result) {
        return (List<TrainingMix.TypeSummary>) result.get("types");
    }

    @SuppressWarnings("unchecked")
    private static List<TrainingMix.RunTypeRow> runs(Map<String, Object> result) {
        return (List<TrainingMix.RunTypeRow>) result.get("runs");
    }
}
