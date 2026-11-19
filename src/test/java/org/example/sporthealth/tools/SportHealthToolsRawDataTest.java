package org.example.sporthealth.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRangeResolver;
import org.junit.jupiter.api.Test;

/**
 * {@code get_run_summary_data} 与 {@code get_run_streams} 的返回结构。
 *
 * <p>用内存仓储喂一段真实形状的 {@code summaryData} 与几条原始流，重点验证：
 * 单位换算对不对（毫秒/卡/分米/哨兵值）、{@code fields} 裁剪与缺失提示、
 * 流目录与分页、以及 {@code pm2} ↔ {@code p-m} 的等价命中。
 */
class SportHealthToolsRawDataTest {

    private static final String RUN_ID = "hw-20261109-0601-12462";

    /** 形状照抄真实导出：含哨兵海拔、心率区间转义字符串、英制/未确认的配速 map。 */
    private static final String SUMMARY = """
            {"sportType":258,"totalTime":2890681,"totalDistance":8292,"totalCalories":592000,
             "totalSteps":8670,"avgPace":348.61,"bestPace":334.0,"avgHeartRate":128,
             "maxHeartRate":142,"minHeartRate":0,"avgStepRate":179,"bestStepRate":183,
             "mTotalDescent":123.0,"mMinAlti":3.4028235E38,"mMaxAlti":1.4E-45,
             "mAvgGroundContactTime":282,"mAvgSwingAngle":-1,"mAverageHangTime":0,
             "paceMap":{"10000165":346.165,"20000343":358.0,"80001386":334.0,"82901435":328.76712},
             "partTimeMap":{"1.0":346.165,"2.0":704.165},
             "britishPaceMap":{"10000272":560.165},
             "heartRateZoneStat":"{\\"warmUp\\":200,\\"fitness\\":15,\\"fatBurn\\":2005,\\"aerobic\\":660,\\"anaerobic\\":0}",
             "wearSportData":{"etraining_effect":26,"anaerobic_exercise_etraining_effect":0,
                              "recovery_time":900,"load_peak":61},
             "mExtendTrackDataMap":{"temperature":"-2","apparentTemperature":"-5",
                                    "relativeHumidity":"40","windSpeed":"6","windForce":"2",
                                    "avg_v_osc":"8.9","avg_gc_tb":"49.6",
                                    "aerobicLoadPeak":"61","trainPoint":"36","activeCalorie":"526000"}}
            """;

    @Test
    void summaryDataIsDecodedWithUnitsAndSentinelNotes() {
        Map<String, Object> result = tools(List.of()).getRunSummaryData(RUN_ID, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals(RUN_ID, result.get("runId"));
        assertTrue((Integer) result.get("bytes") > 0);

        Map<?, ?> decoded = (Map<?, ?>) result.get("decoded");
        assertEquals(2890.7, (Double) decoded.get("durationSeconds"), 1e-6, "totalTime 是毫秒");
        assertEquals("48:11", decoded.get("durationText"));
        assertEquals(8292.0, (Double) decoded.get("distanceMeters"), 1e-6);
        assertEquals(8.29, (Double) decoded.get("distanceKm"), 1e-9);
        assertEquals(592.0, (Double) decoded.get("caloriesKcal"), 1e-9, "totalCalories 单位是卡");
        assertEquals(526.0, (Double) decoded.get("activeCaloriesKcal"), 1e-9);
        assertEquals("5'49\"/km", decoded.get("avgPaceText"));
        assertEquals("5'34\"/km", decoded.get("bestPaceText"));
        assertEquals(12.3, (Double) decoded.get("descentMeters"), 1e-9, "mTotalDescent 是分米");

        Map<?, ?> heartRate = (Map<?, ?>) decoded.get("heartRate");
        assertEquals(128, heartRate.get("avg"));
        assertEquals(142, heartRate.get("max"));
        assertFalse(heartRate.containsKey("min"), "minHeartRate=0 是未采集，不能当成 0 bpm");

        Map<?, ?> cadence = (Map<?, ?>) decoded.get("cadence");
        assertEquals(179, cadence.get("avg"));
        assertEquals(8670, cadence.get("totalSteps"));

        List<?> splits = (List<?>) decoded.get("kmSplits");
        assertEquals(4, splits.size());
        Map<?, ?> first = (Map<?, ?>) splits.get(0);
        assertEquals(1.0, (Double) first.get("endKm"), 1e-9);
        assertEquals(346.2, (Double) first.get("paceSecondsPerKm"), 1e-9);
        assertEquals("5'46\"/km", first.get("paceText"));
        assertEquals(165, first.get("pointIndex"), "键里后四位是轨迹点序号");
        assertEquals(Boolean.FALSE, first.get("partial"));
        Map<?, ?> last = (Map<?, ?>) splits.get(3);
        assertEquals(8.29, (Double) last.get("endKm"), 1e-9);
        assertEquals(0.29, (Double) last.get("distanceKm"), 1e-6);
        assertEquals(Boolean.TRUE, last.get("partial"), "不足一公里的末段要标出来");
        assertEquals(346.2, (Double) first.get("cumulativeSeconds"), 1e-9);

        List<?> zones = (List<?>) decoded.get("heartRateZones");
        assertEquals(4, zones.size(), "anaerobic=0 不占位");
        assertEquals("warmUp", ((Map<?, ?>) zones.get(0)).get("zone"));
        double percentSum = zones.stream()
                .mapToDouble(zone -> (Double) ((Map<?, ?>) zone).get("percent"))
                .sum();
        assertEquals(100.0, percentSum, 0.5);

        Map<?, ?> posture = (Map<?, ?>) decoded.get("posture");
        assertEquals(282.0, (Double) posture.get("groundContactTimeMs"), 1e-9);
        assertEquals(8.9, (Double) posture.get("verticalOscillationCm"), 1e-9);
        assertEquals(49.6, (Double) posture.get("groundBalancePercent"), 1e-9);
        assertFalse(posture.containsKey("swingAngleDeg"), "-1 表示未采集，不能当角度读");

        Map<?, ?> training = (Map<?, ?>) decoded.get("training");
        assertEquals(2.6, (Double) training.get("aerobicTrainingEffect"), 1e-9, "TE 原始值是 ×10 的整数");
        assertEquals(15.0, (Double) training.get("recoveryTimeMinutes"), 1e-9);
        assertEquals(36.0, (Double) training.get("trainingPoints"), 1e-9);

        Map<?, ?> deviceWeather = (Map<?, ?>) decoded.get("deviceWeather");
        assertEquals(-2.0, (Double) deviceWeather.get("temperatureC"), 1e-9);
        assertEquals(40.0, (Double) deviceWeather.get("humidityPercent"), 1e-9);

        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) result.get("notes");
        assertTrue(notes.stream().anyMatch(note -> note.contains("mMinAlti")), "哨兵海拔要提示");
        assertTrue(notes.stream().anyMatch(note -> note.contains("britishPaceMap")), "未确认口径要提示");
    }

    @Test
    void summaryDataSupportsFieldSelectionAndReportsMissingPaths() {
        Map<String, Object> result = tools(List.of()).getRunSummaryData(RUN_ID,
                "bestPace,mExtendTrackDataMap.temperature,mExtendTrackDataMap.hrZ1", null);

        Map<?, ?> fields = (Map<?, ?>) result.get("fields");
        assertEquals(334.0, (Double) fields.get("bestPace"), 1e-9);
        Map<?, ?> extend = (Map<?, ?>) fields.get("mExtendTrackDataMap");
        assertEquals("-2", extend.get("temperature"), "裁剪只保留被点名的子键");
        assertFalse(extend.containsKey("windSpeed"));

        assertEquals(List.of("mExtendTrackDataMap.hrZ1"), result.get("missingFields"));
        assertTrue(((List<?>) result.get("availableFields")).contains("wearSportData"));
        // 换算块基于全部字段，不受 fields 裁剪影响
        assertNotNull(((Map<?, ?>) result.get("decoded")).get("kmSplits"));
    }

    @Test
    void summaryDataCanSkipDecoding() {
        Map<String, Object> result = tools(List.of()).getRunSummaryData(RUN_ID, "avgStepRate", false);

        assertEquals(179, ((Map<?, ?>) result.get("fields")).get("avgStepRate"));
        assertFalse(result.containsKey("decoded"));
        assertFalse(result.containsKey("notes"));
    }

    @Test
    void streamsCatalogListsTypesWithoutDumpingRows() {
        Map<String, Object> result = tools(streams()).getRunStreams(RUN_ID, null, null, null);

        assertEquals("OK", result.get("status"));
        assertEquals(5, result.get("totalRows"));
        List<?> catalog = (List<?>) result.get("streams");
        assertEquals(3, catalog.size(), "rs / rp / pm2 三类");

        Map<?, ?> rs = (Map<?, ?>) catalog.get(0);
        assertEquals("rs", rs.get("type"));
        assertEquals(2, rs.get("rows"));
        assertTrue(String.valueOf(rs.get("meaning")).contains("速度"));
        assertNotNull(rs.get("valueRange"));
        assertNotNull(rs.get("sample"));

        Map<?, ?> pm2 = (Map<?, ?>) catalog.get(2);
        assertEquals("p-m", pm2.get("equivalentType"));
    }

    @Test
    void streamsPagesRowsAndDecodesKnownValues() {
        Map<String, Object> result = tools(streams()).getRunStreams(RUN_ID, "rs", 0, 1);

        assertEquals("OK", result.get("status"));
        assertEquals(2, result.get("total"));
        assertEquals(Boolean.TRUE, result.get("hasMore"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("rows")).get(0);
        assertEquals(0, row.get("seq"));
        assertEquals(5, row.get("pointIndex"), "rs 的 k 是轨迹点序号，没有毫秒时间戳");
        assertFalse(row.containsKey("timeMillis"));
        assertEquals(27.68, (Double) row.get("value"), 1e-9);
        assertEquals(9.96, (Double) row.get("speedKmh"), 1e-9, "v × 0.36 = km/h");
        assertEquals("6'01\"/km", row.get("paceText"));

        Map<?, ?> pm2Row = (Map<?, ?>) ((List<?>) tools(streams())
                .getRunStreams(RUN_ID, "pm2", 0, 5).get("rows")).get(0);
        assertEquals(1000.0, (Double) pm2Row.get("endDistanceMeters"), 1e-9);
        assertEquals("5'46\"/km", pm2Row.get("paceText"));
        assertEquals(346.165, (Double) pm2Row.get("value"), 1e-9);
    }

    @Test
    void streamsFallBackToEquivalentType() {
        Map<String, Object> result = tools(streams()).getRunStreams(RUN_ID, "p-m", null, null);

        assertEquals("OK", result.get("status"));
        assertEquals("pm2", result.get("type"), "只有 pm2 时，p-m 请求要落到等价的 pm2");
        assertEquals("p-m", result.get("requestedType"));
        assertNotNull(result.get("typeNote"));
    }

    @Test
    void streamsReportAvailableTypesForUnknownType() {
        Map<String, Object> result = tools(streams()).getRunStreams(RUN_ID, "nope", null, null);

        assertEquals("NO_DATA", result.get("status"));
        assertEquals(List.of("rs", "rp", "pm2"), result.get("availableTypes"));
    }

    @Test
    void tcxRecordWithoutHuaweiPayloadReturnsNoData() {
        SportHealthTools tcxOnly = tools(List.of(), null);

        assertEquals("NO_DATA", tcxOnly.getRunSummaryData(RUN_ID, null, null).get("status"));
        assertEquals("NO_DATA", tcxOnly.getRunStreams(RUN_ID, null, null, null).get("status"));
    }

    /** 三条流共 5 行：rs 两行、rp 一行、pm2 两行。 */
    private static List<TrackStream> streams() {
        return List.of(
                stream("rs", 0, 5, 27.68, Map.of("tp", "rs", "v", "27.68", "k", "5")),
                stream("rs", 1, 10, 28.25, Map.of("tp", "rs", "v", "28.25", "k", "10")),
                stream("rp", 0, 16, null, Map.of("tp", "rp", "k", "16", "gct", "283",
                        "vo", "9.3", "gtb", "50.0", "ee", "-101")),
                stream("pm2", 0, 165, 346.165, Map.of("tp", "pm2", "d", "1000", "n", "165", "p", "346.165")),
                stream("pm2", 1, 343, 358.0, Map.of("tp", "pm2", "d", "2000", "n", "343", "p", "358.0")));
    }

    private static TrackStream stream(String type, int seq, int pointIndex, Double value,
                                      Map<String, String> fields) {
        return new TrackStream(RUN_ID, type, seq, null, pointIndex, value, fields);
    }

    private SportHealthTools tools(List<TrackStream> rawStreams) {
        return tools(rawStreams, SUMMARY);
    }

    private SportHealthTools tools(List<TrackStream> rawStreams, String summaryData) {
        RunActivity run = new RunActivity(RUN_ID, "2026-11-08T22:01:38Z", "户外跑步",
                LocalDateTime.of(2026, 11, 9, 6, 1).toLocalDate(),
                LocalDateTime.of(2026, 11, 9, 6, 1), LocalDateTime.of(2026, 11, 9, 6, 49),
                Instant.parse("2026-11-08T22:01:38Z"), 2890.681, 8292d, 30d, 12.3d,
                "0", "OUTDOOR", 1435, 10.34, 348.6, 39.9057, 116.3972, 39.906, 116.398,
                48.2, 55.1, "hw-20261109-0601-12462.json", "HUAWEI_HEALTH",
                128, 142, null, 179, 8670, 592d, "12462", 258, 4, summaryData);
        RunDataset dataset = new RunDataset(Instant.parse("2026-11-19T07:00:00Z"), "memory",
                List.of(), List.of(run), List.of());
        HealthDataRepository fake = new HealthDataRepository() {
            @Override
            public RunDataset dataset() {
                return dataset;
            }

            @Override
            public void reload() {
                // 内存实现，无需重新扫描
            }

            @Override
            public List<TrackPoint> route(String runId) {
                return List.of();
            }

            @Override
            public List<TrackStream> streams(String runId, String streamType) {
                return rawStreams.stream()
                        .filter(row -> streamType == null || streamType.equals(row.streamType()))
                        .toList();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T07:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RunAnalyticsService service = new RunAnalyticsService(fake, new DateRangeResolver(clock));
        HealthDataProperties properties =
                new HealthDataProperties("classpath:tcx-dataset", null, "Asia/Shanghai", 20, 200);
        return new SportHealthTools(service, fake, properties, null);
    }

    @Test
    void decodedBlocksAreNullSafeForMinimalSummary() {
        Map<String, Object> result = tools(List.of(), "{\"sportType\":258}")
                .getRunSummaryData(RUN_ID, null, null);

        assertEquals("OK", result.get("status"));
        Map<?, ?> decoded = (Map<?, ?>) result.get("decoded");
        assertTrue(decoded.isEmpty(), "只有 sportType 时不应编造任何换算字段: " + decoded);
        assertEquals(List.of(), result.get("notes"));
    }
}
