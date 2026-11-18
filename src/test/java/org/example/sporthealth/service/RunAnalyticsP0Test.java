package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RacePrediction;
import org.example.sporthealth.domain.RunClassification;
import org.example.sporthealth.domain.RunSplits;
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.domain.TrainingMix;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0 分析能力：比赛预测、训练负荷、坡度修正配速、训练类型识别。
 */
class RunAnalyticsP0Test {

    private RunAnalyticsService service;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new RunAnalyticsService(repository, new DateRangeResolver(clock));
    }

    @Test
    void predictsStandardRaceTimesFromBestEfforts() {
        RacePrediction prediction = service.predictRace(service.resolveRange(null, null), null);

        assertEquals("riegel", prediction.method());
        assertEquals(1.06, prediction.exponent(), 1e-9);
        assertFalse(prediction.references().isEmpty());
        assertEquals(4, prediction.predictions().size(), "默认输出 5K/10K/半马/全马");

        RacePrediction.Prediction fiveK = prediction.predictions().get(0);
        assertEquals(5000d, fiveK.targetMeters(), 1e-6);
        assertEquals("ACTUAL", fiveK.source(), "5 公里有实测最佳努力");

        RacePrediction.Prediction tenK = prediction.predictions().get(1);
        assertEquals(10000d, tenK.targetMeters(), 1e-6);
        assertEquals("ACTUAL", tenK.source(), "11-01 跑出了 10K 最佳");

        RacePrediction.Prediction half = prediction.predictions().get(2);
        RacePrediction.Prediction full = prediction.predictions().get(3);
        assertEquals("RIEGEL", half.source());
        assertEquals("RIEGEL", full.source());
        assertTrue(half.predictedSeconds() > tenK.predictedSeconds());
        assertTrue(full.predictedSeconds() > half.predictedSeconds());
        assertTrue(prediction.text().contains("预测"));
    }

    @Test
    void predictsCustomDistance() {
        RacePrediction prediction = service.predictRace(
                service.resolveRange(null, null), 12d);

        assertEquals(1, prediction.predictions().size());
        RacePrediction.Prediction twelveK = prediction.predictions().get(0);
        assertEquals(12000d, twelveK.targetMeters(), 1e-6);
        assertEquals("RIEGEL", twelveK.source());
        assertTrue(twelveK.predictedSeconds() > 0);
        assertNotNull(twelveK.paceText());
    }

    @Test
    void computesAcwrAndFlagsShortCoverage() {
        TrainingLoad load = service.trainingLoad(LocalDate.of(2026, 11, 3), "distance");

        assertEquals(LocalDate.of(2026, 11, 3), load.anchorDate());
        assertEquals("distance", load.metric());
        assertEquals(3, load.acute().runCount(), "最近 7 天：10-28、11-01、11-03");
        assertEquals(5, load.chronic().runCount(), "慢性窗口覆盖全部 5 条记录");
        assertEquals(24.1, load.acute().load(), 0.05);
        assertEquals(38.28, load.chronic().load(), 0.05);
        assertEquals("HIGH_RISK", load.riskLevel());
        assertTrue(load.acwr() > 2.0);
        assertFalse(load.dataSufficient(), "样本数据只有 10 天，不足以稳定解读 ACWR");
        assertEquals("LOW", load.confidence());
        assertTrue(load.weeklyRampPercent() > 50, "最近一周比前一周明显加量");
        assertEquals(4, load.restDaysLast7());
        assertEquals(1, load.consecutiveTrainingDays(), "11-02 没有跑步，连续天数从 11-03 断开");
        assertNotNull(load.monotony());
        assertNotNull(load.strain());
        assertTrue(load.recommendation().contains("数据只覆盖"));
        assertTrue(load.text().contains("ACWR"));
    }

    @Test
    void addsGradeAdjustedPaceToSplits() {
        RunSplits splits = service.splits(
                service.find("20261025户外跑步").orElseThrow(), 1000d);

        assertNotNull(splits.avgGradeAdjustedPaceSecondsPerKm());
        assertNotNull(splits.avgGradeAdjustedPaceText());
        assertTrue(splits.splits().stream().allMatch(split -> split.gradePercent() != null));
        assertTrue(splits.splits().stream()
                .allMatch(split -> split.gradeAdjustedPaceSecondsPerKm() != null));
        assertTrue(splits.splits().stream()
                .anyMatch(split -> split.netElevationMeters() != null
                        && Math.abs(split.netElevationMeters()) > 0.1),
                "样本轨迹既有上坡也有下坡");
    }

    @Test
    void classifiesLongRunAndBuildsTrainingMix() {
        RunClassification longRun = service.classify(
                service.find("20261101户外跑步").orElseThrow());

        assertEquals("LONG_RUN", longRun.type());
        assertEquals("长距离跑", longRun.typeText());
        assertFalse(longRun.reasons().isEmpty());
        assertNotNull(longRun.gradeAdjustedPaceSecondsPerKm());

        TrainingMix mix = service.trainingMix(service.resolveRange(null, null));

        assertEquals(5, mix.totalRuns());
        assertEquals(5, mix.runs().size());
        assertEquals(5, mix.types().stream()
                .mapToInt(TrainingMix.TypeSummary::runCount).sum());
        assertTrue(mix.types().stream().anyMatch(type -> "LONG_RUN".equals(type.type())));
        assertTrue(mix.text().contains("共 5 次跑步"));
    }
}
