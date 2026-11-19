package org.example.sporthealth.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.example.sporthealth.domain.RunLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code run_level} 的读取：日期解析、最近一天语义、速度换算配速、成绩格式化。
 *
 * <p>用真实 SQLite 而不是 mock：{@code ORDER BY record_day} 的字符串排序、
 * 可空 REAL 列的读取都是数据库侧行为，也正是最容易踩坑的地方。
 */
class RunLevelStoreTest {

    @TempDir
    Path tempDir;

    private JdbcClient jdbc;
    private RunLevelStore store;

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
        store = new RunLevelStore(jdbc);
    }

    @Test
    void readsLatestSnapshotWithZonesAndOfficialTimes() {
        level("20261106", 44.0, 12.9, 9.98, 13.95, 11.15, 15.2,
                418, 805, 1375, 2820, 6170, 13000);
        level("20261110", 46.0, 13.0716, 10.1094, 14.1015, 11.3048, 15.3722,
                416, 801, 1367, 2806, 6133, 12926);

        RunLevel latest = store.latest().orElseThrow();

        assertEquals(LocalDate.of(2026, 11, 10), latest.date());
        assertEquals(46.0, latest.score(), 1e-9);
        assertEquals(5, latest.zones().size());
        RunLevel.Zone zoneA = latest.zones().get(0);
        assertEquals("A", zoneA.code());
        assertEquals("有氧耐力", zoneA.name());
        assertEquals(13.0716, zoneA.speedKmh(), 1e-9);
        // 13.0716 km/h -> 275.4 秒/公里 -> 4'35"/km
        assertEquals(275.4, zoneA.paceSecondsPerKm(), 1e-9);
        assertEquals("4'35\"/km", zoneA.paceText());

        assertEquals(6, latest.officialTimes().size());
        assertEquals("6:56", latest.officialTimes().get(0).timeText());
        RunLevel.OfficialTime marathon = latest.officialTimes().get(5);
        assertEquals(42.195, marathon.distanceKm(), 1e-9);
        // 超过 1 小时必须保留秒，不能被截成「3 小时 35 分」
        assertEquals("3:35:26", marathon.timeText());
        assertEquals(306.3, marathon.paceSecondsPerKm(), 1e-9);
    }

    @Test
    void picksLatestSnapshotOnOrBeforeGivenDay() {
        level("20261106", 44.0, 12.9, 9.98, 13.95, 11.15, 15.2,
                418, 805, 1375, 2820, 6170, 13000);
        level("20261109", 45.0, 13.0, 10.05, 14.05, 11.2, 15.3,
                417, 803, 1370, 2810, 6150, 12950);

        assertEquals(LocalDate.of(2026, 11, 6),
                store.latestOnOrBefore(LocalDate.of(2026, 11, 7)).orElseThrow().date());
        assertEquals(LocalDate.of(2026, 11, 9),
                store.latestOnOrBefore(LocalDate.of(2026, 11, 9)).orElseThrow().date());
        assertTrue(store.latestOnOrBefore(LocalDate.of(2026, 11, 5)).isEmpty(),
                "早于所有快照时应返回空，而不是硬取第一行");
    }

    @Test
    void returnsBetweenInAscendingOrder() {
        level("20261106", 44.0, 12.9, 9.98, 13.95, 11.15, 15.2,
                418, 805, 1375, 2820, 6170, 13000);
        level("20261109", 45.0, 13.0, 10.05, 14.05, 11.2, 15.3,
                417, 803, 1370, 2810, 6150, 12950);
        level("20261110", 46.0, 13.07, 10.1, 14.1, 11.3, 15.37,
                416, 801, 1367, 2806, 6133, 12926);

        List<RunLevel> rows = store.between(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9));

        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 11, 6), rows.get(0).date());
        assertEquals(LocalDate.of(2026, 11, 9), rows.get(1).date());
    }

    @Test
    void skipsRowsWithUnparseableDate() {
        level("20261110", 46.0, 13.07, 10.1, 14.1, 11.3, 15.37,
                416, 801, 1367, 2806, 6133, 12926);
        jdbc.sql("INSERT INTO run_level (record_day, excel_row) VALUES ('bad-date', 2)").update();

        assertEquals(1, store.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).size());
    }

    @Test
    void omitsZonesAndTimesWithNullValues() {
        jdbc.sql("""
                INSERT INTO run_level (record_day, excel_row, current_run_level, a_speed)
                VALUES ('20261110', 1, 40.0, 12.0)
                """).update();

        RunLevel latest = store.latest().orElseThrow();

        assertEquals(1, latest.zones().size(), "只有 A 档有值，其余不输出");
        assertTrue(latest.officialTimes().isEmpty());
    }

    /**
     * 应用里的连接池只有 1 条连接（SQLite 单写者），用 {@code stream()} 查询会把连接占住不还，
     * 连续两次查询就会超时。这里用真实的单连接 Hikari 池复现/防回归。
     */
    @Test
    void repeatedReadsDoNotExhaustSingleConnectionPool() throws Exception {
        Path dbFile = tempDir.resolve("pool").resolve("sport-health.db");
        Files.createDirectories(dbFile.getParent());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(2_000);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcClient pooled = JdbcClient.create(dataSource);
            new SqliteSchema(pooled, dataSource).ensure();
            pooled.sql("""
                    INSERT INTO run_level (record_day, excel_row, current_run_level)
                    VALUES ('20261110', 1, 46.0)
                    """).update();
            RunLevelStore pooledStore = new RunLevelStore(pooled);

            for (int i = 0; i < 5; i++) {
                assertTrue(pooledStore.latest().isPresent());
                assertTrue(pooledStore.latestOnOrBefore(LocalDate.of(2026, 11, 10)).isPresent());
                assertEquals(1, pooledStore.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).size());
            }
        }
    }

    private void level(String day, double score,
                       double a, double e, double i, double m, double r,
                       double km1, double km3, double km5, double km10, double half, double marathon) {
        jdbc.sql("""
                INSERT INTO run_level (
                    record_day, excel_row, current_run_level, a_speed, e_speed, i_speed, m_speed, r_speed,
                    km1_seconds, km3_seconds, km5_seconds, km10_seconds,
                    half_marathon_seconds, marathon_seconds
                ) VALUES (:day, 1, :score, :a, :e, :i, :m, :r, :km1, :km3, :km5, :km10, :half, :marathon)
                """)
                .param("day", day)
                .param("score", score)
                .param("a", a)
                .param("e", e)
                .param("i", i)
                .param("m", m)
                .param("r", r)
                .param("km1", km1)
                .param("km3", km3)
                .param("km5", km5)
                .param("km10", km10)
                .param("half", half)
                .param("marathon", marathon)
                .update();
    }
}
