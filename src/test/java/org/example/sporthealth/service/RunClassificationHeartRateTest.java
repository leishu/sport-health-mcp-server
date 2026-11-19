package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunClassification;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.Test;

/**
 * 心率区间对训练类型判定的交叉验证：区间换算、支持/冲突信号、以及「配速像轻松跑但心率偏高」的改判。
 *
 * <p>用内存仓储构造记录，不走 TCX 样本——样本里没有心率字段，覆盖不到这条路径。
 */
class RunClassificationHeartRateTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 5 次 5 公里的记录，中位配速 360 秒/公里，静息心率 50、数据集最高心率 180。 */
    private static final int RESTING = 50;
    private static final int MAX_REFERENCE = 180;

    private RunAnalyticsService service(List<RunActivity> runs) {
        HealthDataRepository repository = new HealthDataRepository() {
            @Override
            public RunDataset dataset() {
                return new RunDataset(Instant.parse("2026-11-19T00:00:00Z"), "memory",
                        runs.stream().map(RunActivity::sourceFile).toList(), runs, List.of());
            }

            @Override
            public void reload() {
                // 内存仓储无需重新扫描
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return List.of();
            }

            @Override
            public Double restingHeartRate() {
                return (double) RESTING;
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T04:00:00Z"), ZONE);
        return new RunAnalyticsService(repository, new DateRangeResolver(clock));
    }

    private static RunActivity run(String id, int day, int paceSeconds, Integer avgHeartRate, int maxHeartRate) {
        LocalDateTime start = LocalDateTime.of(2026, 11, day, 7, 0);
        double duration = paceSeconds * 5d;
        return new RunActivity(id, id, "户外跑步", start.toLocalDate(), start,
                start.plusSeconds((long) duration), start.atZone(ZONE).toInstant(),
                duration, 5000d, null, null, "OUTDOOR", "OUTDOOR", 0,
                paceSeconds > 0 ? 3600d / paceSeconds : null, (double) paceSeconds,
                null, null, null, null, null, null, id + ".json", "HUAWEI_HEALTH",
                avgHeartRate, maxHeartRate, null, null, null, null, null, 258, null, null);
    }

    /** 5 条记录：中位配速 360，最高心率 180 提供最大心率基准。 */
    private List<RunActivity> baseRuns(RunActivity target) {
        List<RunActivity> runs = new ArrayList<>();
        runs.add(target);
        runs.add(run("hw-20261101-0700-1", 1, 360, 128, 150));
        runs.add(run("hw-20261102-0700-2", 2, 360, 130, MAX_REFERENCE));
        runs.add(run("hw-20261103-0700-3", 3, 360, 126, 148));
        runs.add(run("hw-20261104-0700-4", 4, 360, 132, 152));
        return runs;
    }

    @Test
    void usesReserveHeartRateZoneWhenRestingHeartRateIsAvailable() {
        RunActivity target = run("hw-20261105-0700-9", 5, 360, 130, 150);
        RunClassification result = service(baseRuns(target)).classify(target);

        assertEquals("EASY", result.type());
        RunClassification.HeartRate heartRate = result.heartRate();
        assertNotNull(heartRate, "华为来源的记录带平均心率，应当给出区间判定");
        assertEquals("HRR", heartRate.percentBasis());
        assertEquals(RESTING, heartRate.restingHeartRate());
        assertEquals(MAX_REFERENCE, heartRate.maxHeartRateReference());
        // (130 - 50) / (180 - 50) = 61.5%
        assertEquals(61.5d, heartRate.percent(), 0.1d);
        assertEquals("Z2", heartRate.zone());
        assertEquals("SUPPORT", heartRate.signal());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("平均心率 130 次/分")),
                result.reasons().toString());
    }

    @Test
    void tempoRunIsSupportedByRhythmZone() {
        RunActivity target = run("hw-20261105-0700-9", 5, 340, 150, 168);
        RunClassification result = service(baseRuns(target)).classify(target);

        assertEquals("TEMPO", result.type());
        assertEquals("Z3", result.heartRate().zone());
        assertEquals("SUPPORT", result.heartRate().signal());
    }

    @Test
    void highHeartRateReclassifiesEasyPaceAsTempo() {
        // 配速就是个人中位（360），单看配速是轻松跑；平均心率 165 已到 Z4（88.5% 储备心率）
        RunActivity target = run("hw-20261105-0700-9", 5, 360, 165, 178);
        RunClassification result = service(baseRuns(target)).classify(target);

        assertEquals("Z4", result.heartRate().zone());
        assertEquals("TEMPO", result.type(), "心率已进阈值区，改判节奏跑");
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("实际强度更接近节奏跑")),
                result.reasons().toString());
    }

    @Test
    void withoutHeartRateTheBlockStaysNull() {
        RunActivity target = run("hw-20261105-0700-9", 5, 360, null, 0);
        RunClassification result = service(baseRuns(target)).classify(target);

        assertNull(result.heartRate(), "TCX 来源没有心率，不能编一个区间出来");
        assertEquals("EASY", result.type());
    }

    @Test
    void intervalIsJudgedByPeakHeartRateNotAverage() {
        // 间歇跑有大量恢复段，平均心率被稀释到 Z2；峰值顶到 95% 才是真信号
        assertEquals("SUPPORT", RunAnalyticsService.heartRateSignal("INTERVAL", 1, 95d));
        assertEquals("NEUTRAL", RunAnalyticsService.heartRateSignal("INTERVAL", 1, 88d));
        assertEquals("CONFLICT", RunAnalyticsService.heartRateSignal("INTERVAL", 1, 80d));
        // 平均心率本身已进 Z4 时，没有最高心率也算支持
        assertEquals("SUPPORT", RunAnalyticsService.heartRateSignal("INTERVAL", 3, null));
    }

    @Test
    void easyRunAboveThresholdZoneConflicts() {
        assertEquals("SUPPORT", RunAnalyticsService.heartRateSignal("EASY", 1, 70d));
        assertEquals("NEUTRAL", RunAnalyticsService.heartRateSignal("EASY", 2, 80d));
        assertEquals("CONFLICT", RunAnalyticsService.heartRateSignal("EASY", 3, 88d));
    }
}
