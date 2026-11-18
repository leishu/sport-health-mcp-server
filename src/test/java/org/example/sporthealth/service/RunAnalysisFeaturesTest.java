package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.BestEfforts;
import org.example.sporthealth.domain.Consistency;
import org.example.sporthealth.domain.ElevationProfile;
import org.example.sporthealth.domain.PeriodSummary;
import org.example.sporthealth.domain.PersonalBestEffort;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunFilter;
import org.example.sporthealth.domain.RunSplits;
import org.example.sporthealth.domain.StopPoints;
import org.example.sporthealth.domain.TrainingTrend;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 分段、最佳努力、海拔、停顿、趋势、连续性与过滤排序的行为测试。
 */
class RunAnalysisFeaturesTest {

    private RunAnalyticsService service;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new RunAnalyticsService(repository, new DateRangeResolver(clock));
    }

    private RunActivity run(String id) {
        return service.find(id).orElseThrow();
    }

    @Test
    void splitsAreDerivedFromGpsAndCoverWholeDistance() {
        RunSplits splits = service.splits(run("20261025户外跑步"), 1000d);

        assertTrue(splits.gpsDistanceKm() > 7.0, "GPS 累计距离应在 7 公里左右");
        assertEquals(1, splits.splits().get(0).index());
        assertEquals(0.0, splits.splits().get(0).fromKm(), 1e-6);
        assertEquals(splits.gpsDistanceKm(),
                splits.splits().stream().mapToDouble(split -> split.distanceKm()).sum(), 0.01);
        assertNotNull(splits.fastest());
        assertNotNull(splits.slowest());
        assertTrue(splits.fastest().paceSecondsPerKm() <= splits.slowest().paceSecondsPerKm());
        assertNotNull(splits.negativeSplit());
        assertNotNull(splits.paceStdDevSeconds());
        assertTrue(splits.splits().get(splits.splits().size() - 1).partial()
                || splits.gpsDistanceKm() % 1.0 < 0.001);
    }

    @Test
    void calculatesBestEffortsAndMarksTargetsBeyondDistance() {
        BestEfforts efforts = service.bestEfforts(run("20261025户外跑步"),
                List.of(1000d, 5000d, 10000d));

        assertEquals(2, efforts.efforts().size());
        assertEquals(1000d, efforts.efforts().get(0).targetMeters(), 1e-6);
        assertEquals(5000d, efforts.efforts().get(1).targetMeters(), 1e-6);
        assertEquals(7.13, efforts.gpsDistanceKm(), 0.01);
        assertTrue(efforts.efforts().get(1).durationSeconds() > efforts.efforts().get(0).durationSeconds());
        assertEquals(List.of(10000d), efforts.unavailableTargets());
    }

    @Test
    void findsBestEffortsAcrossRuns() {
        List<PersonalBestEffort> efforts = service.effortBests(
                service.resolveRange(null, null), List.of(1000d, 5000d, 10000d));

        assertEquals(3, efforts.size());
        assertEquals(1000d, efforts.get(0).targetMeters(), 1e-6);
        assertEquals(5000d, efforts.get(1).targetMeters(), 1e-6);
        assertEquals(10000d, efforts.get(2).targetMeters(), 1e-6);
        assertEquals("20261027户外跑步", efforts.get(0).runId(), "1 公里最佳来自配速最快的那次");
        assertEquals("20261027户外跑步", efforts.get(1).runId());
        assertEquals("20261101户外跑步", efforts.get(2).runId(), "只有 10 公里以上才可能跑出 10K 最佳");
        assertEquals(LocalDate.of(2026, 10, 27), efforts.get(0).date());
        assertTrue(efforts.get(0).durationSeconds() < efforts.get(1).durationSeconds());
    }

    @Test
    void buildsElevationProfileWithSmoothing() {
        ElevationProfile profile = service.elevationProfile(run("20261025户外跑步"), 100d, 1d);

        assertNotNull(profile.minAltitudeMeters());
        assertNotNull(profile.maxAltitudeMeters());
        assertTrue(profile.totalGainMeters() > 0);
        assertFalse(profile.buckets().isEmpty());
        assertEquals(profile.gpsDistanceKm(),
                profile.buckets().get(profile.buckets().size() - 1).toKm(), 0.01);
        assertTrue(profile.buckets().stream().anyMatch(bucket -> bucket.avgAltitudeMeters() != null));
    }

    @Test
    void detectsNoFalseStopsOnContinuousTrack() {
        StopPoints stops = service.stopPoints(run("20261025户外跑步"), 30d, 30d);

        assertEquals(0, stops.stopCount());
        assertEquals(0.0, stops.totalStopSeconds(), 1e-6);
        assertTrue(stops.stops().isEmpty());
    }

    @Test
    void buildsWeeklyTrendAndComparesPeriods() {
        TrainingTrend trend = service.trend("week", 4, LocalDate.of(2026, 11, 3));

        assertEquals("week", trend.period());
        assertEquals(4, trend.periods().size());
        assertEquals(5, trend.periods().stream().mapToInt(period -> period.runCount()).sum());
        assertEquals(LocalDate.of(2026, 11, 2), trend.periods().get(3).from());
        assertNotNull(trend.periods().get(3).distanceDeltaPercent());
        assertTrue(trend.text().contains("相比上一周期"));
    }

    @Test
    void buildsMonthlyTrend() {
        TrainingTrend trend = service.trend("month", 3, LocalDate.of(2026, 11, 3));

        assertEquals(3, trend.periods().size());
        PeriodSummary october = service.grouped(service.resolveRange(null, null), "month").get(0);
        assertEquals(LocalDate.of(2026, 10, 1), october.from());
        assertEquals(3, october.runCount());
        assertEquals(2026, trend.periods().get(2).from().getYear());
        assertEquals(11, trend.periods().get(2).from().getMonthValue());
    }

    @Test
    void reportsConsistencyAnchoredAtLastRun() {
        Consistency consistency = service.consistency(service.resolveRange(null, null));

        assertEquals(5, consistency.activeDays());
        assertEquals(10, consistency.totalDays());
        assertEquals(50.0, consistency.activeRatePercent(), 0.01);
        assertEquals(2, consistency.longestStreak().days(), "10-27 与 10-28 连续两天");
        assertEquals(1, consistency.currentStreak().days());
        assertEquals(LocalDate.of(2026, 11, 3), consistency.currentStreak().to());
        assertEquals(2.25, consistency.avgGapDays(), 0.001);
        assertEquals(LocalDate.of(2026, 11, 3), consistency.lastActiveDate());
    }

    @Test
    void groupsByWeekAndMonth() {
        DateRange range = service.resolveRange(null, null);

        List<PeriodSummary> weeks = service.grouped(range, "week");
        List<PeriodSummary> months = service.grouped(range, "month");

        assertEquals(3, weeks.size());
        assertEquals(1, weeks.get(0).runCount());
        assertEquals(3, weeks.get(1).runCount());
        assertEquals(1, weeks.get(2).runCount());
        assertEquals(2, months.size());
        assertEquals(3, months.get(0).runCount());
        assertEquals(2, months.get(1).runCount());
    }

    @Test
    void filtersAndSortsRunList() {
        DateRange range = service.resolveRange(null, null);

        assertEquals(5, service.runs(range, new RunFilter("户外跑步", null, null, null, null, null, null)).size());
        assertEquals(2, service.runs(range, new RunFilter(null, 8d, null, null, null, null, null)).size());

        List<RunActivity> byDistance = service.runs(range,
                new RunFilter(null, null, null, null, null, null, "distance"));
        assertEquals("20261101户外跑步", byDistance.get(0).id());

        List<RunActivity> byPace = service.runs(range,
                new RunFilter(null, null, null, null, null, null, "pace"));
        assertEquals("20261027户外跑步", byPace.get(0).id());

        assertEquals(2, service.runs(range,
                new RunFilter(null, null, null, null, null, 2, null)).size(), "周二有 10-27 和 11-03 两次");
    }
}
