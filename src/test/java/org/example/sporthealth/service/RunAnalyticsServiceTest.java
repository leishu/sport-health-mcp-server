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
import org.example.sporthealth.domain.DailyRunSummary;
import org.example.sporthealth.domain.PersonalBests;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunComparison;
import org.example.sporthealth.domain.RunDetail;
import org.example.sporthealth.domain.RunLookup;
import org.example.sporthealth.domain.RunSummary;
import org.example.sporthealth.importer.TcxParser;
import org.example.sporthealth.repository.TcxRunRepository;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunAnalyticsServiceTest {

    private RunAnalyticsService service;

    @BeforeEach
    void setUp() {
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        TcxRunRepository repository = new TcxRunRepository(properties, new TcxParser(properties));
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new RunAnalyticsService(repository, new DateRangeResolver(clock));
    }

    @Test
    void loadsEveryTcxFileFromClasspath() {
        assertEquals(5, service.dataset().runCount());
        assertEquals(5, service.dataset().sourceFiles().size());
        assertTrue(service.dataset().warnings().isEmpty(), service.dataset().warnings().toString());
    }

    @Test
    void defaultsToWholeDatasetRange() {
        DateRange range = service.resolveRange(null, null);

        assertEquals(LocalDate.of(2026, 10, 25), range.from());
        assertEquals(LocalDate.of(2026, 11, 3), range.to());
    }

    @Test
    void summarizesWholeRange() {
        RunSummary summary = service.summary(service.resolveRange(null, null));

        assertEquals(5, summary.runCount());
        assertEquals(5, summary.recordedDays());
        assertEquals(38.28, summary.totalDistanceKm(), 0.001);
        assertEquals(219.8, summary.totalDurationMinutes(), 0.05);
        assertEquals(7.66, summary.avgDistanceKm(), 0.001);
        assertEquals(344.6, summary.avgPaceSecondsPerKm(), 0.1);
        assertEquals("5'45\"/km", summary.avgPaceText());
        assertEquals("20261101户外跑步", summary.longestRunId());
        assertEquals(10.7, summary.longestDistanceKm(), 0.001);
        assertEquals("20261027户外跑步", summary.bestPaceRunId());
        assertEquals(LocalDate.of(2026, 10, 25), summary.firstRunDate());
        assertEquals(LocalDate.of(2026, 11, 3), summary.lastRunDate());
    }

    @Test
    void breaksDownByLocalDate() {
        List<DailyRunSummary> days = service.daily(service.resolveRange(null, null));

        assertEquals(5, days.size());
        assertEquals(LocalDate.of(2026, 10, 25), days.get(0).date());
        assertEquals(LocalDate.of(2026, 11, 3), days.get(4).date());
        assertEquals(7.12, days.get(0).distanceKm(), 0.001);
        assertEquals("5'44\"/km", days.get(0).paceText());
    }

    @Test
    void filtersByExplicitRange() {
        RunSummary summary = service.summary(service.resolveRange("2026-11-01", "2026-11-03"));

        assertEquals(2, summary.runCount());
        assertEquals(18.96, summary.totalDistanceKm(), 0.001);
        assertEquals(3, summary.dayCount());
    }

    @Test
    void findsRunByPartialId() {
        RunActivity run = service.find("20261101").orElseThrow();

        assertEquals("20261101户外跑步", run.id());
        assertTrue(service.find("不存在的记录").isEmpty());
    }

    @Test
    void reportsAmbiguousPartialIdInsteadOfPickingOne() {
        RunLookup lookup = service.lookup("2026");

        assertTrue(lookup.isAmbiguous());
        assertEquals(5, lookup.candidateIds().size());
        assertFalse(lookup.isFound());

        RunLookup unique = service.lookup("20261101");
        assertTrue(unique.isFound());
        assertEquals("20261101户外跑步", unique.run().id());
    }

    @Test
    void exposesPersonalBests() {
        PersonalBests bests = service.bests(service.resolveRange(null, null));

        assertEquals("20261101户外跑步", bests.longestDistance().id());
        assertEquals("20261027户外跑步", bests.fastestPace().id());
        assertEquals("20261101户外跑步", bests.longestDuration().id());
    }

    @Test
    void comparesTwoRuns() {
        RunActivity base = service.find("20261025户外跑步").orElseThrow();
        RunActivity other = service.find("20261027户外跑步").orElseThrow();

        RunComparison comparison = service.compare(base, other);

        assertNotNull(comparison.paceDiffSecondsPerKm());
        assertTrue(comparison.paceDiffSecondsPerKm() < 0, "10-27 的配速应该更快");
        assertTrue(comparison.verdict().contains("20261027户外跑步"));
    }

    @Test
    void pagesTrackPoints() {
        RunActivity run = service.find("20261025户外跑步").orElseThrow();

        RunDetail detail = service.detail(run, 0, 100);

        assertNotNull(detail.route());
        assertEquals(1216, detail.route().total());
        assertEquals(100, detail.route().items().size());
        assertTrue(detail.route().hasMore());
        assertEquals("5'44\"/km", detail.paceText());
    }
}
