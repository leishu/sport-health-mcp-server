package org.example.sporthealth.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.support.RouteTypes;
import org.junit.jupiter.api.Test;

class TcxParserTest {

    private static final Path DATA_DIR = Path.of("src/test/resources/tcx-dataset");

    private final TcxParser parser = new TcxParser(new HealthDataProperties(null, null, null, 0, 0));

    @Test
    void parsesLapSummaryAndTrackpoints() {
        List<ParsedRun> runs = parser.parse(DATA_DIR.resolve("20261025户外跑步.tcx"));

        assertEquals(1, runs.size());
        RunActivity run = runs.get(0).activity();
        assertEquals("20261025户外跑步", run.id());
        assertEquals("户外跑步", run.sport());
        assertEquals(LocalDate.of(2026, 10, 25), run.date());
        assertEquals(2448.0, run.durationSeconds(), 0.01);
        assertEquals(7123.0, run.distanceMeters(), 0.01);
        assertEquals(10.1, run.climbMeters(), 0.05);
        assertEquals(13.5, run.descentMeters(), 0.05);
        assertEquals("0", run.routeType());
        assertEquals(RouteTypes.OUTDOOR, run.routeTypeText());
        assertEquals("Health", run.creator());
        assertEquals(1216, run.trackPointCount());
        assertEquals(1216, runs.get(0).trackPoints().size());
        assertEquals(39.902286, run.startLatitude(), 1e-6);
        assertEquals(116.18434, run.startLongitude(), 1e-6);
        assertNotNull(run.minAltitudeMeters());
        assertNotNull(run.maxAltitudeMeters());
        // 2448 秒 / 7.123 公里 ≈ 343.7 秒每公里
        assertEquals(343.7, run.avgPaceSecondsPerKm(), 0.2);
        assertEquals(10.5, run.avgSpeedKmh(), 0.1);
        assertTrue(runs.get(0).warnings().isEmpty(), runs.get(0).warnings().toString());
    }

    @Test
    void convertsUtcTimestampToLocalDate() {
        // TCX 内为 2026-10-31T23:12:42Z，北京时间已是 11 月 1 日，与文件名一致
        RunActivity run = parser.parse(DATA_DIR.resolve("20261101户外跑步.tcx")).get(0).activity();

        assertEquals(LocalDate.of(2026, 11, 1), run.date());
        assertEquals(LocalTime.of(7, 12, 42), run.startTime().toLocalTime().truncatedTo(ChronoUnit.SECONDS));
        assertEquals(Instant.parse("2026-10-31T23:12:42Z"), run.startTimeUtc());
    }

    @Test
    void parsesEveryExportedFile() {
        List<String> files = List.of(
                "20261025户外跑步.tcx",
                "20261027户外跑步.tcx",
                "20261028户外跑步.tcx",
                "20261101户外跑步.tcx",
                "20261103户外跑步.tcx");

        double totalDistance = 0;
        double totalDuration = 0;
        for (String file : files) {
            List<ParsedRun> runs = parser.parse(DATA_DIR.resolve(file));
            assertEquals(1, runs.size(), file);
            assertEquals("Health", runs.get(0).activity().creator(), file);
            assertEquals(RouteTypes.OUTDOOR, runs.get(0).activity().routeTypeText(), file);
            assertTrue(runs.get(0).warnings().isEmpty(), file + " -> " + runs.get(0).warnings());
            totalDistance += runs.get(0).activity().distanceMeters();
            totalDuration += runs.get(0).activity().durationSeconds();
        }

        assertEquals(38278.0, totalDistance, 0.1);
        assertEquals(13190.0, totalDuration, 0.1);
        assertTrue(totalDistance > 0 && totalDuration > 0);
    }

    @Test
    void readsEveryTrackInsideOneLap() {
        ParsedRun run = parseXml("""
                <TrainingCenterDatabase creator="Health">
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T00:00:00Z</Id>
                      <Lap StartTime="2026-01-01T00:00:00Z">
                        <TotalTimeSeconds>120</TotalTimeSeconds>
                        <DistanceMeters>400</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-01-01T00:00:00Z</Time><Position><LatitudeDegrees>39.9</LatitudeDegrees><LongitudeDegrees>116.1</LongitudeDegrees></Position></Trackpoint>
                          <Trackpoint><Time>2026-01-01T00:01:00Z</Time><Position><LatitudeDegrees>39.901</LatitudeDegrees><LongitudeDegrees>116.101</LongitudeDegrees></Position></Trackpoint>
                        </Track>
                        <Track>
                          <Trackpoint><Time>2026-01-01T00:02:00Z</Time><Position><LatitudeDegrees>39.902</LatitudeDegrees><LongitudeDegrees>116.102</LongitudeDegrees></Position></Trackpoint>
                        </Track>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """).get(0);

        assertEquals(3, run.activity().trackPointCount());
        assertEquals(3, run.trackPoints().size());
    }

    @Test
    void fallsBackToActivityIdWhenLapHasNoStartTime() {
        ParsedRun run = parseXml("""
                <TrainingCenterDatabase creator="Health">
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T02:00:00Z</Id>
                      <Lap>
                        <TotalTimeSeconds>60</TotalTimeSeconds>
                        <DistanceMeters>100</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-01-01T02:00:00Z</Time><Position><LatitudeDegrees>39.9</LatitudeDegrees><LongitudeDegrees>116.1</LongitudeDegrees></Position></Trackpoint>
                          <Trackpoint><Time>2026-01-01T02:01:00Z</Time><Position><LatitudeDegrees>39.901</LatitudeDegrees><LongitudeDegrees>116.101</LongitudeDegrees></Position></Trackpoint>
                        </Track>
                      </Lap>
                      <Lap StartTime="2026-01-01T02:01:00Z">
                        <DistanceMeters>50</DistanceMeters>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """).get(0);

        // 第二个 Lap 的 StartTime 不能覆盖活动开始时间，应由 Activity/Id 兜底
        assertEquals(Instant.parse("2026-01-01T02:00:00Z"), run.activity().startTimeUtc());
        assertEquals(LocalDate.of(2026, 1, 1), run.activity().date());
        assertEquals(LocalTime.of(10, 0), run.activity().startTime().toLocalTime());
    }

    @Test
    void fallsBackToTrackTimeWhenSomeLapsLackDuration() {
        ParsedRun run = parseXml("""
                <TrainingCenterDatabase>
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T00:00:00Z</Id>
                      <Lap StartTime="2026-01-01T00:00:00Z">
                        <TotalTimeSeconds>60</TotalTimeSeconds>
                        <DistanceMeters>100</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-01-01T00:00:00Z</Time></Trackpoint>
                          <Trackpoint><Time>2026-01-01T00:01:00Z</Time></Trackpoint>
                        </Track>
                      </Lap>
                      <Lap>
                        <DistanceMeters>100</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-01-01T00:01:00Z</Time></Trackpoint>
                          <Trackpoint><Time>2026-01-01T00:02:00Z</Time></Trackpoint>
                        </Track>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """).get(0);

        // 不能把 60 秒当成总时长
        assertEquals(120.0, run.activity().durationSeconds(), 0.01);
        assertTrue(run.warnings().stream().anyMatch(w -> w.contains("TotalTimeSeconds")));
    }

    @Test
    void reportsFieldLevelProblemsInsteadOfDroppingSilently() {
        ParsedRun run = parseXml("""
                <TrainingCenterDatabase>
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T00:00:00Z</Id>
                      <Lap StartTime="2026-01-01T00:00:00Z">
                        <TotalTimeSeconds>60</TotalTimeSeconds>
                        <DistanceMeters>100</DistanceMeters>
                        <Track>
                          <Trackpoint>
                            <Time>2026-01-01T00:00:00Z</Time>
                            <AltitudeMeters>not-a-number</AltitudeMeters>
                          </Trackpoint>
                          <Trackpoint>
                            <Position><LatitudeDegrees>39.9</LatitudeDegrees><LongitudeDegrees>116.1</LongitudeDegrees></Position>
                          </Trackpoint>
                        </Track>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """).get(0);

        // 缺 Time 的轨迹点被跳过，其余保留
        assertEquals(1, run.trackPoints().size());
        TrackPoint point = run.trackPoints().get(0);
        assertNull(point.latitude());
        assertNull(point.longitude());
        assertNull(point.altitudeMeters());
        assertFalse(point.hasPosition());

        String warnings = String.join(" | ", run.warnings());
        assertTrue(warnings.contains("缺少 Time"), warnings);
        assertTrue(warnings.contains("缺少经纬度"), warnings);
        assertTrue(warnings.contains("不是合法值"), warnings);
        assertTrue(warnings.contains("缺少 AltitudeMeters"), warnings);
    }

    @Test
    void keepsCoordinatesAsNullInsteadOfNaN() {
        ParsedRun run = parseXml("""
                <TrainingCenterDatabase>
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T00:00:00Z</Id>
                      <Lap StartTime="2026-01-01T00:00:00Z">
                        <TotalTimeSeconds>60</TotalTimeSeconds>
                        <DistanceMeters>100</DistanceMeters>
                        <Track>
                          <Trackpoint><Time>2026-01-01T00:00:00Z</Time></Trackpoint>
                        </Track>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """).get(0);

        TrackPoint point = run.trackPoints().get(0);
        assertNull(point.latitude());
        assertNull(point.longitude());
    }

    @Test
    void splitsMultipleActivitiesInOneFile() {
        List<ParsedRun> runs = parseXml("""
                <TrainingCenterDatabase>
                  <Activities>
                    <Activity Sport="户外跑步">
                      <Id>2026-01-01T00:00:00Z</Id>
                      <Lap StartTime="2026-01-01T00:00:00Z">
                        <TotalTimeSeconds>60</TotalTimeSeconds><DistanceMeters>100</DistanceMeters>
                      </Lap>
                    </Activity>
                    <Activity Sport="骑行">
                      <Id>2026-01-02T00:00:00Z</Id>
                      <Lap StartTime="2026-01-02T00:00:00Z">
                        <TotalTimeSeconds>120</TotalTimeSeconds><DistanceMeters>500</DistanceMeters>
                      </Lap>
                    </Activity>
                  </Activities>
                </TrainingCenterDatabase>
                """);

        assertEquals(2, runs.size());
        assertEquals("multi#1", runs.get(0).activity().id());
        assertEquals("multi#2", runs.get(1).activity().id());
        assertEquals("骑行", runs.get(1).activity().sport());
    }

    @Test
    void mapsUnknownRouteTypeWithoutDroppingIt() {
        assertEquals(RouteTypes.OUTDOOR, RouteTypes.text("0"));
        assertEquals(RouteTypes.INDOOR, RouteTypes.text("1"));
        assertEquals("UNKNOWN(7)", RouteTypes.text("7"));
        assertNull(RouteTypes.text(null));
    }

    private List<ParsedRun> parseXml(String xml) {
        return parser.parse("multi.tcx", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
