package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.example.sporthealth.domain.OfficialRaceComparison;
import org.example.sporthealth.domain.RacePrediction;
import org.example.sporthealth.domain.RunLevel;
import org.example.sporthealth.domain.RunLevelHistory;
import org.example.sporthealth.repository.RunLevelStore;
import org.example.sporthealth.repository.SqliteSchema;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * 官方跑力的展示口径：来源标识、差值方向、「基本一致」的判定边界、快照时效与趋势文本。
 */
class RunLevelServiceTest {

    @TempDir
    Path tempDir;

    private RunLevelService service;
    private JdbcClient jdbc;

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
        service = new RunLevelService(new RunLevelStore(jdbc));
        level("20261108", 44.0, 13000);
        level("20261109", 45.0, 12950);
        level("20261110", 46.0, 12926);
    }

    @Test
    void comparesOfficialAndRiegelRowByRow() {
        RacePrediction riegel = new RacePrediction(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10),
                "riegel", 1.06, List.of(),
                List.of(prediction(5000, 1400), prediction(10000, 2806), prediction(12000, 3400)), "");

        OfficialRaceComparison comparison = service.compare(riegel,
                new DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)));

        assertEquals("HUAWEI_OFFICIAL", comparison.source());
        assertEquals(LocalDate.of(2026, 11, 10), comparison.date());
        assertTrue(comparison.withinRange());
        assertEquals(0, comparison.staleDays());
        assertEquals(2, comparison.rows().size(), "12 公里没有官方档位，不进入 rows");
        assertEquals(List.of(12d), comparison.unavailableTargetsKm());

        OfficialRaceComparison.Row fiveK = comparison.rows().get(0);
        assertEquals(1367d, fiveK.officialSeconds(), 1e-9);
        assertEquals("22:47", fiveK.officialText());
        assertEquals(273.4, fiveK.officialPaceSecondsPerKm(), 1e-9);
        assertEquals("HUAWEI_OFFICIAL", fiveK.officialSource());
        assertEquals("RIEGEL", fiveK.riegelSource());
        // 官方 1367 秒，Riegel 1400 秒：官方快 33 秒
        assertEquals(-33d, fiveK.deltaSeconds(), 1e-9);
        assertEquals("比 Riegel 快 33 秒", fiveK.deltaText());

        OfficialRaceComparison.Row tenK = comparison.rows().get(1);
        assertEquals("与 Riegel 一致", tenK.deltaText());
        assertTrue(comparison.text().startsWith("华为官方跑力 46.0（2026-11-10）"));
        assertTrue(comparison.text().contains("12 公里官方无对应档位"), comparison.text());
    }

    @Test
    void namesActualBaselineWhenTargetWasMeasured() {
        RacePrediction riegel = new RacePrediction(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10),
                "riegel", 1.06, List.of(), List.of(prediction(5000, 1400, "ACTUAL")), "");

        OfficialRaceComparison comparison = service.compare(riegel,
                new DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)));

        assertEquals("比区间实测快 33 秒", comparison.rows().get(0).deltaText(),
                "目标距离本身有实测时，基线是实测而不是 Riegel 外推");
    }

    @Test
    void marksSnapshotOutsideRange() {
        RacePrediction riegel = new RacePrediction(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 15),
                "riegel", 1.06, List.of(), List.of(prediction(5000, 1400)), "");

        OfficialRaceComparison comparison = service.compare(riegel,
                new DateRange(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 15)));

        assertFalse(comparison.withinRange());
        assertEquals(5, comparison.staleDays());
        assertTrue(comparison.text().contains("不在所选区间内"), comparison.text());
    }

    @Test
    void returnsNullWhenNoSnapshotBeforeRange() {
        RacePrediction riegel = new RacePrediction(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                "riegel", 1.06, List.of(), List.of(prediction(5000, 1400)), "");

        assertNull(service.compare(riegel, new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))));
    }

    @Test
    void buildsHistoryWithChange() {
        RunLevelHistory history = service.history(LocalDate.of(2026, 11, 10), 30);

        assertEquals(3, history.days());
        assertEquals(2.0, history.scoreChange(), 1e-9);
        assertEquals(LocalDate.of(2026, 11, 8), history.points().get(0).date());
        assertTrue(history.text().contains("升到 46.0"), history.text());
    }

    @Test
    void historyDefaultsTo90DaysEndingAtLatest() {
        RunLevelHistory history = service.history(null, null);

        assertEquals(LocalDate.of(2026, 8, 13), history.from());
        assertEquals(LocalDate.of(2026, 11, 10), history.to());
    }

    @Test
    void describesZonesAndOfficialTimes() {
        RunLevel level = service.latest().orElseThrow();

        String text = service.describe(level);

        assertTrue(text.contains("华为官方跑力 46.0（2026-11-10）"), text);
        assertTrue(text.contains("A 4'35\"/km"), text);
        assertTrue(text.contains("全程马拉松 3:35:26"), text);
    }

    private void level(String day, double score, double marathonSeconds) {
        jdbc.sql("""
                INSERT INTO run_level (
                    record_day, excel_row, current_run_level, a_speed, e_speed, i_speed, m_speed, r_speed,
                    km1_seconds, km3_seconds, km5_seconds, km10_seconds,
                    half_marathon_seconds, marathon_seconds
                ) VALUES (:day, 1, :score, 13.0716, 10.1094, 14.1015, 11.3048, 15.3722,
                          416, 801, 1367, 2806, 6133, :marathon)
                """)
                .param("day", day)
                .param("score", score)
                .param("marathon", marathonSeconds)
                .update();
    }

    private static RacePrediction.Prediction prediction(double meters, double seconds) {
        return prediction(meters, seconds, "RIEGEL");
    }

    private static RacePrediction.Prediction prediction(double meters, double seconds, String source) {
        double pace = seconds / (meters / 1000d);
        return new RacePrediction.Prediction(meters, PaceFormat.round(meters / 1000d, 3), source, seconds,
                PaceFormat.clock(seconds), PaceFormat.round(pace, 1), PaceFormat.pace(pace), "HIGH",
                meters, "run-1", LocalDate.of(2026, 11, 1), seconds);
    }
}
