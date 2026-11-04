package org.example.sporthealth.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SQLite 表结构与版本迁移。
 *
 * <p>只维护两张表：
 * <ul>
 *   <li>{@code run_activity}：一次运动的全部摘要字段，外加来源文件、内容哈希与导入时间；</li>
 *   <li>{@code track_point}：轨迹点，主键 {@code (run_id, seq)}，{@code WITHOUT ROWID} 节省空间，
 *       一个活动的轨迹行在物理上连续存放。</li>
 * </ul>
 *
 * <p>版本号写在 {@code PRAGMA user_version}：0 表示空库，直接建表；未来加心率、步频等列时
 * 追加一段迁移脚本即可，不需要 Flyway。
 *
 * <p>DDL 不在事务里执行：连接池上限是 1（SQLite 单写者），事务已占住唯一连接，
 * 迁移过程中再向 {@link DataSource} 要连接会一直等到超时。逐条自动提交配合
 * {@code IF NOT EXISTS} 与「重复列忽略」已能保证重跑安全。
 */
@Component
@Slf4j
public class SqliteSchema {

    /** 当前结构版本，改动 {@link #MIGRATIONS} 时同步递增。 */
    static final int SCHEMA_VERSION = 2;

    /** 索引 {@code i} 对应从版本 {@code i} 升级到 {@code i + 1} 的脚本。 */
    private static final List<String> MIGRATIONS = List.of("""
            CREATE TABLE IF NOT EXISTS source_file (
                source_file     TEXT PRIMARY KEY,
                size            INTEGER NOT NULL,
                modified_millis INTEGER NOT NULL,
                content_hash    TEXT,
                synced_at       TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS run_activity (
                id                  TEXT PRIMARY KEY,
                activity_id         TEXT NOT NULL,
                sport               TEXT NOT NULL,
                run_date            TEXT NOT NULL,
                start_time          TEXT NOT NULL,
                end_time            TEXT NOT NULL,
                start_time_utc      TEXT NOT NULL,
                duration_seconds    REAL NOT NULL,
                distance_meters     REAL NOT NULL,
                climb_meters        REAL,
                descent_meters      REAL,
                route_type          TEXT,
                route_type_text     TEXT,
                track_point_count   INTEGER NOT NULL,
                avg_speed_kmh       REAL,
                avg_pace_seconds    REAL,
                start_latitude      REAL,
                start_longitude     REAL,
                end_latitude        REAL,
                end_longitude       REAL,
                min_altitude_meters REAL,
                max_altitude_meters REAL,
                source_file         TEXT NOT NULL,
                creator             TEXT,
                content_hash        TEXT NOT NULL,
                imported_at         TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_run_activity_date ON run_activity (run_date, start_time);
            CREATE INDEX IF NOT EXISTS idx_run_activity_source ON run_activity (source_file);
            CREATE INDEX IF NOT EXISTS idx_run_activity_hash ON run_activity (content_hash);

            CREATE TABLE IF NOT EXISTS track_point (
                run_id         TEXT NOT NULL,
                seq            INTEGER NOT NULL,
                timestamp      TEXT NOT NULL,
                latitude       REAL,
                longitude      REAL,
                altitude       REAL,
                PRIMARY KEY (run_id, seq)
            ) WITHOUT ROWID;
            """,
            """
            ALTER TABLE source_file ADD COLUMN status TEXT NOT NULL DEFAULT 'OK';
            ALTER TABLE source_file ADD COLUMN error_message TEXT;
            """);

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    private volatile boolean initialized;

    public SqliteSchema(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** 幂等：多个仓储实例或测试共用同一个库时只执行一次。 */
    public void ensure() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            migrate();
            initialized = true;
        }
    }

    private void migrate() {
        int current = jdbc.sql("PRAGMA user_version").query(Integer.class).single();
        if (current > SCHEMA_VERSION) {
            throw new IllegalStateException("数据库结构版本 " + current + " 高于程序支持的 " + SCHEMA_VERSION
                    + "，请升级程序或换一个 db-path");
        }
        if (current == SCHEMA_VERSION) {
            return;
        }

        // DDL 用原生 Statement 执行：sqlite-jdbc 的多语句脚本只认 Statement，走 JdbcClient 会静默建不成表。
        // 这里刻意不包事务：连接池上限为 1 时，事务内再取连接会等到超时（详见类注释）。
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (int version = current; version < SCHEMA_VERSION; version++) {
                for (String sql : splitStatements(MIGRATIONS.get(version))) {
                    execute(statement, sql);
                }
            }
            statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 SQLite 结构失败", e);
        }
        log.info("SQLite 结构已从版本 {} 升级到 {}", current, SCHEMA_VERSION);
    }

    /**
     * 执行一条 DDL。SQLite 的 {@code ADD COLUMN} 没有 {@code IF NOT EXISTS}，
     * 上一次迁移在第 N 条失败后重跑会撞上「列已存在」，这种错误必须视为成功。
     */
    private static void execute(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                log.debug("列已存在，跳过: {}", sql);
                return;
            }
            throw e;
        }
    }

    /** 按分号拆分脚本，去掉空行与注释，保证一条 Statement 只执行一句 DDL。 */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        for (String raw : script.split(";")) {
            String sql = raw.strip();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }
}
