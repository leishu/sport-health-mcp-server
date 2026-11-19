package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.DynamicsSegment;
import org.example.sporthealth.domain.DynamicsSummary;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.RunningDynamics;
import org.example.sporthealth.domain.RunningDynamicsDetail;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.repository.HealthDataRepository;
import org.junit.jupiter.api.Test;

/**
 * 跑姿与步幅的口径：
 * <ul>
 *   <li>{@code rp} 的 {@code k} 是相对起跑的秒数，要按轨迹插值落回距离轴再分段；</li>
 *   <li>分段步幅 = 本段速度 × 60 ÷ 本段步频，整次步幅 = 距离 ÷ 步数；</li>
 *   <li>区间汇总是「每条记录一个样本」，不让长跑的采样行数压过短跑。</li>
 * </ul>
 */
class RunningDynamicsServiceTest {

    private static final String RUN_ID = "hw-20260701-0600-1";

    /** 1000 米、250 秒：每 10 秒一个轨迹点，每步 40 米，步频 180。 */
    private static final int STEPS = 25;
    private static final double METERS_PER_STEP = 40d;
    private static final int CADENCE = 180;

    @Test
    void detailSplitsByDistanceAndComparesHalves() {
        RunningDynamicsService service = new RunningDynamicsService(repository(route(), samples()));

        RunningDynamicsDetail detail = service.detail(run(), 500d);

        assertEquals(1.0, detail.run().distanceKm(), 1e-9);
        assertEquals(280.0, detail.run().groundContactTimeMs(), 1e-9);
        assertEquals(9.2, detail.run().verticalOscillationCm(), 1e-9);
        assertEquals(49.6, detail.run().groundBalancePercent(), 1e-9);
        assertEquals(49, detail.run().sampleCount(), "rp 每 5 秒一个采样，250 秒共 49 个");

        assertEquals(2, detail.segments().size(), "1000 米按 500 米切正好两段");
        DynamicsSegment first = detail.segments().get(0);
        assertEquals(0.0, first.fromKm(), 1e-9);
        assertEquals(0.5, first.toKm(), 1e-9);
        assertEquals(250.0, first.paceSecondsPerKm(), 0.5, "4 m/s 即 250 秒/公里");
        assertEquals(CADENCE, first.cadenceSpm());
        assertEquals(1.333, first.strideLengthMeters(), 0.002, "4 m/s × 60 ÷ 180");
        assertEquals(280.0, first.groundContactTimeMs(), 1e-9, "前半程采样 gct=280");
        assertEquals(24, first.sampleCount(), "k=7…122 共 24 个采样点落在前半程");
        assertFalse(first.partial());

        DynamicsSegment second = detail.segments().get(1);
        assertEquals(290.0, second.groundContactTimeMs(), 1e-9, "k=127 起落在后半程");
        assertEquals(25, second.sampleCount());

        assertNotNull(detail.firstHalf());
        assertNotNull(detail.secondHalf());
        assertEquals(280.0, detail.firstHalf().groundContactTimeMs(), 1e-9);
        assertEquals(290.0, detail.secondHalf().groundContactTimeMs(), 1e-9);
    }

    @Test
    void detailFallsBackToSamplesWhenSummaryHasNoPosture() {
        RunningDynamicsService service = new RunningDynamicsService(repository(route(), samples()));

        // summaryData 没有跑姿块（老导入/只存了 rp），整次均值改用采样均值
        RunningDynamicsDetail detail = service.detail(runWithoutPosture(), 1000d);

        assertTrue(detail.run().hasPosture());
        assertEquals(285.1, detail.run().groundContactTimeMs(), 0.01, "(280×24 + 290×25) / 49");
        assertEquals(9.2, detail.run().verticalOscillationCm(), 1e-9);
        assertEquals(49, detail.run().sampleCount());
    }

    @Test
    void detailWithoutRouteKeepsSummaryButNoSegments() {
        RunningDynamicsService service = new RunningDynamicsService(repository(List.of(), samples()));

        RunningDynamicsDetail detail = service.detail(run(), 1000d);

        assertTrue(detail.segments().isEmpty());
        assertNull(detail.firstHalf());
        assertEquals(280.0, detail.run().groundContactTimeMs(), 1e-9, "没有轨迹时整次汇总仍然有效");
    }

    @Test
    void summaryAveragesRunsNotSamples() {
        List<RunningDynamics> rows = List.of(
                row("a", LocalDate.of(2026, 6, 13), 272d, 9.3, 49.5, 0.95, 178),
                row("b", LocalDate.of(2026, 6, 20), 278d, 9.2, 49.1, 0.96, 179),
                row("c", LocalDate.of(2026, 7, 1), 282d, 9.1, 48.9, 0.98, 180));

        DynamicsSummary summary = new RunningDynamicsService(repository(List.of(), List.of()))
                .summarize(rows);

        assertEquals(3, summary.runCount());
        assertEquals(3, summary.postureRunCount());
        assertEquals(3, summary.strideRunCount());
        assertEquals(277.33, summary.groundContactTimeMs().average(), 1e-9);
        assertEquals(272d, summary.groundContactTimeMs().min(), 1e-9);
        assertEquals(282d, summary.groundContactTimeMs().max(), 1e-9);
        assertEquals(178d, summary.cadenceSpm().min(), 1e-9);
        assertEquals(180d, summary.cadenceSpm().max(), 1e-9);
    }

    @Test
    void summaryIgnoresMissingMetrics() {
        List<RunningDynamics> rows = List.of(
                row("a", LocalDate.of(2026, 6, 13), 272d, null, null, 0.95, 178),
                row("b", LocalDate.of(2026, 6, 20), null, null, null, null, null));

        DynamicsSummary summary = new RunningDynamicsService(repository(List.of(), List.of()))
                .summarize(rows);

        assertEquals(2, summary.runCount());
        assertEquals(1, summary.postureRunCount());
        assertEquals(1, summary.groundContactTimeMs().count());
        assertNull(summary.verticalOscillationCm(), "一项都没有时不返回 0 值统计");
        assertEquals(1, summary.strideRunCount());
    }

    @Test
    void trendGroupsByMonthAndWeek() {
        List<RunningDynamics> rows = List.of(
                row("a", LocalDate.of(2026, 6, 13), 272d, 9.3, 49.5, 0.95, 178),
                row("b", LocalDate.of(2026, 6, 20), 278d, 9.2, 49.1, 0.96, 179),
                row("c", LocalDate.of(2026, 7, 1), 282d, 9.1, 48.9, 0.98, 180));
        RunningDynamicsService service = new RunningDynamicsService(repository(List.of(), List.of()));

        List<DynamicsSummary.Period> months = service.trend(rows, "month");
        assertEquals(2, months.size());
        assertEquals("2026-06", months.get(0).key());
        assertEquals(2, months.get(0).runCount());
        assertEquals(275d, months.get(0).groundContactTimeMs(), 1e-9);
        assertEquals(LocalDate.of(2026, 6, 1), months.get(0).from());
        assertEquals(LocalDate.of(2026, 6, 30), months.get(0).to());
        assertEquals("2026-07", months.get(1).key());

        List<DynamicsSummary.Period> weeks = service.trend(rows, "week");
        assertEquals(3, weeks.size(), "三周各一次");
        assertEquals(1, weeks.get(0).runCount());
        assertTrue(weeks.get(0).key().startsWith("2026-W"), weeks.get(0).key());
        assertEquals(LocalDate.of(2026, 6, 8), weeks.get(0).from(), "ISO 周从周一开始");
    }

    private static RunningDynamics row(String id, LocalDate date, Double gct, Double vo, Double gtb,
                                       Double stride, Integer cadence) {
        return new RunningDynamics(id, date, 5d, 300d, "5'00\"/km", cadence, stride, gct, vo, gtb, 0);
    }

    /** 1000 米、250 秒，每 10 秒一个点，步频 180：4 m/s。 */
    private static List<TrackPoint> route() {
        List<TrackPoint> points = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 6, 0);
        for (int i = 0; i <= STEPS; i++) {
            double meters = i * METERS_PER_STEP;
            // 1 米纬度约 1/111320 度，按累计距离递增，haversine 累加回同样的米数
            points.add(new TrackPoint(start.plusSeconds(i * 10L), 39.9 + meters / 111_320d, 116.4,
                    50d, 140, CADENCE, null));
        }
        return points;
    }

    /** 每 5 秒一个跑姿采样：前半程 gct=280、后半程 290。 */
    private static List<TrackStream> samples() {
        List<TrackStream> rows = new ArrayList<>();
        int seq = 0;
        for (int k = 7; k < 250; k += 5) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("tp", "rp");
            fields.put("k", String.valueOf(k));
            fields.put("gct", k < 125 ? "280" : "290");
            fields.put("vo", "9.2");
            fields.put("gtb", "49.6");
            fields.put("ee", "-101");
            rows.add(new TrackStream(RUN_ID, "rp", seq++, null, k, null, fields));
        }
        return rows;
    }

    private static RunActivity run() {
        return run("{\"sportType\":258,\"totalTime\":250000,\"totalDistance\":1000,"
                + "\"avgStepRate\":180,\"totalSteps\":1100,\"avgPace\":250.0,"
                + "\"mAvgGroundContactTime\":280,"
                + "\"mExtendTrackDataMap\":{\"avg_v_osc\":\"9.2\",\"avg_gc_tb\":\"49.6\"}}");
    }

    /** 没有跑姿块的 summaryData：只有时长/距离/步数，用来验证「用采样均值补」。 */
    private static RunActivity runWithoutPosture() {
        return run("{\"sportType\":258,\"totalTime\":250000,\"totalDistance\":1000,"
                + "\"avgStepRate\":180,\"totalSteps\":1100,\"avgPace\":250.0}");
    }

    private static RunActivity run(String summary) {
        return new RunActivity(RUN_ID, "2026-06-30T22:00:00Z", "户外跑步",
                LocalDate.of(2026, 7, 1), LocalDateTime.of(2026, 7, 1, 6, 0),
                LocalDateTime.of(2026, 7, 1, 6, 4, 10), Instant.parse("2026-06-30T22:00:00Z"),
                250d, 1000d, 0d, 0d, "0", "OUTDOOR", STEPS + 1, 14.4, 250d,
                39.9, 116.4, 39.9, 116.4, 50d, 50d, "hw-20260701-0600-1.json", "HUAWEI_HEALTH",
                140, 150, null, 180, 1100, 60d, "1", 258, 4, summary);
    }

    private static HealthDataRepository repository(List<TrackPoint> route, List<TrackStream> streams) {
        RunDataset dataset = new RunDataset(Instant.parse("2026-11-19T07:00:00Z"), "memory",
                List.of(), List.of(), List.of());
        return new HealthDataRepository() {
            @Override
            public RunDataset dataset() {
                return dataset;
            }

            @Override
            public void reload() {
                // 内存实现
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return route;
            }

            @Override
            public List<TrackStream> streams(String runId, String streamType) {
                return streams.stream()
                        .filter(row -> streamType == null || streamType.equals(row.streamType()))
                        .toList();
            }
        };
    }
}
