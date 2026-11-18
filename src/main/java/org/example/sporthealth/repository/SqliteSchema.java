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
 * <p>只维护七张表：
 * <ul>
 *   <li>{@code run_activity}：一次运动的全部摘要字段，外加来源文件、内容哈希与导入时间。
 *       来自华为 JSON 导出的记录还会带上心率、步数、卡路里、运动类型码等列；</li>
 *   <li>{@code track_point}：轨迹点，主键 {@code (run_id, seq)}，{@code WITHOUT ROWID} 节省空间，
 *       一个活动的轨迹行在物理上连续存放；华为 JSON 轨迹额外带上逐点心率、步频与累计距离；</li>
 *   <li>{@code source_file}：来源文件指纹（TCX 与华为 JSON 共用），增量同步的比对基准；</li>
 *   <li>{@code run_weather}：起跑与结束两个时刻的天气，一行对应一次跑步，由 open-meteo 回填；</li>
 *   <li>{@code health_metric}：华为健康明细的逐点指标（心率、睡眠分期、血氧、压力……），
 *       一行一个采样点，主键 {@code (record_id, metric_key, start_time)} 负责跨副本去重；</li>
 *   <li>{@code activity_minute}：华为「每分钟运动数据」，一行一分钟一种运动类型，用于步数与活动量分析；</li>
 *   <li>{@code health_metric_type}：指标字典，把 {@code type_code + metric_key} 翻译成人话，
 *       由 v4 迁移写入种子数据，{@code describe_data_source} 与数据字典文档都以它为准。</li>
 * </ul>
 *
 * <p>版本号写在 {@code PRAGMA user_version}：0 表示空库，直接建表；后续加字段时
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
    static final int SCHEMA_VERSION = 6;

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
            """,
            """
            CREATE TABLE IF NOT EXISTS run_weather (
                run_id                  TEXT PRIMARY KEY,
                start_obs_time_utc      TEXT,
                end_obs_time_utc        TEXT,
                start_latitude          REAL,
                start_longitude         REAL,
                end_latitude            REAL,
                end_longitude           REAL,
                start_temperature_c     REAL,
                start_apparent_c        REAL,
                start_humidity_percent  REAL,
                start_precipitation_mm  REAL,
                start_wind_speed_kmh    REAL,
                start_wind_direction    INTEGER,
                start_weather_code      INTEGER,
                end_temperature_c       REAL,
                end_apparent_c          REAL,
                end_humidity_percent    REAL,
                end_precipitation_mm    REAL,
                end_wind_speed_kmh      REAL,
                end_wind_direction      INTEGER,
                end_weather_code        INTEGER,
                provider                TEXT NOT NULL DEFAULT 'open-meteo',
                fetched_at              TEXT NOT NULL,
                fetched_epoch_millis    INTEGER NOT NULL,
                status                  TEXT NOT NULL,
                error_message           TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_run_weather_status
                ON run_weather (status, fetched_epoch_millis);
            """,
            """
            ALTER TABLE run_activity ADD COLUMN sport_family TEXT NOT NULL DEFAULT 'RUN';
            ALTER TABLE run_activity ADD COLUMN sport_type_code INTEGER;
            ALTER TABLE run_activity ADD COLUMN sub_type INTEGER;
            ALTER TABLE run_activity ADD COLUMN total_steps INTEGER;
            ALTER TABLE run_activity ADD COLUMN total_calories_kcal REAL;
            ALTER TABLE run_activity ADD COLUMN avg_heart_rate INTEGER;
            ALTER TABLE run_activity ADD COLUMN max_heart_rate INTEGER;
            ALTER TABLE run_activity ADD COLUMN min_heart_rate INTEGER;
            ALTER TABLE run_activity ADD COLUMN avg_cadence INTEGER;
            ALTER TABLE run_activity ADD COLUMN record_id TEXT;
            ALTER TABLE run_activity ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'tcx';

            ALTER TABLE track_point ADD COLUMN heart_rate INTEGER;
            ALTER TABLE track_point ADD COLUMN cadence INTEGER;
            ALTER TABLE track_point ADD COLUMN distance_meters REAL;

            ALTER TABLE source_file ADD COLUMN kind TEXT NOT NULL DEFAULT 'tcx';
            CREATE INDEX IF NOT EXISTS idx_source_file_kind ON source_file (kind);

            CREATE TABLE IF NOT EXISTS health_metric (
                record_id   TEXT NOT NULL,
                metric_key  TEXT NOT NULL,
                start_time  TEXT NOT NULL,
                end_time    TEXT NOT NULL,
                type_code   INTEGER NOT NULL,
                value_num   REAL,
                value_text  TEXT,
                unit        TEXT,
                device_code TEXT,
                time_zone   TEXT,
                source_file TEXT NOT NULL,
                PRIMARY KEY (record_id, metric_key, start_time)
            ) WITHOUT ROWID;

            CREATE INDEX IF NOT EXISTS idx_health_metric_key ON health_metric (metric_key, start_time);
            CREATE INDEX IF NOT EXISTS idx_health_metric_start ON health_metric (start_time);

            CREATE TABLE IF NOT EXISTS activity_minute (
                start_time       TEXT NOT NULL,
                sport_type       INTEGER NOT NULL,
                record_day       INTEGER NOT NULL,
                end_time         TEXT NOT NULL,
                steps            INTEGER,
                distance_meters  INTEGER,
                calories_kcal    REAL,
                duration_minutes INTEGER,
                is_intensity     INTEGER,
                is_stand         INTEGER,
                altitude         REAL,
                device_code      TEXT,
                source_file      TEXT NOT NULL,
                PRIMARY KEY (start_time, sport_type)
            ) WITHOUT ROWID;

            CREATE INDEX IF NOT EXISTS idx_activity_minute_day ON activity_minute (record_day, sport_type);

            CREATE TABLE IF NOT EXISTS health_metric_type (
                type_code   INTEGER NOT NULL,
                metric_key  TEXT NOT NULL,
                metric_name TEXT NOT NULL,
                category    TEXT NOT NULL,
                unit        TEXT,
                note        TEXT,
                PRIMARY KEY (type_code, metric_key)
            );

            INSERT OR REPLACE INTO health_metric_type (type_code, metric_key, metric_name, category, unit, note) VALUES
                (5, 'BLOODPRESSURE', '血压', '血压', 'mmHg', '手机手动录入，收缩压/舒张压在 value JSON 内'),
                (10002, 'BLOODPRESSURE', '血压', '血压', 'mmHg', 'type=5 的平台副本，recordId 相同'),
                (7, 'DYNAMIC_HEART_RATE', '动态心率', '心率', 'bpm', '逐分钟自动采样；type=7 与 500023 是同一批数据，统一写规范键 DYNAMIC_HEART_RATE'),
                (500023, 'DYNAMIC_HEART_RATE', '动态心率', '心率', 'bpm', '与 type=7 重复，导入时按规范键去重，只会留下先到的一份'),
                (500024, 'RESTING_HEART_RATE', '静息心率', '心率', 'bpm', '每日一条'),
                (500044, 'HEART_RATE_VARIABILITY', '心率变异性 RMSSD', '心率', 'ms', '单次测量'),
                (500051, 'AFIB_BURDEN_RESULT', '房颤负荷', '心率', '%', '正常心搏数与房颤占比'),
                (500007, 'ARRHYTHMIA_RESULT', '心律失常筛查', '心率', '', '早搏筛查结果'),
                (9, 'PROFESSIONAL_SLEEP_SHALLOW', '睡眠-浅睡', '睡眠', '', '一段连续时长，start/end 即区间'),
                (9, 'PROFESSIONAL_SLEEP_DEEP', '睡眠-深睡', '睡眠', '', '一段连续时长，start/end 即区间'),
                (9, 'PROFESSIONAL_SLEEP_DREAM', '睡眠-快速眼动', '睡眠', '', '一段连续时长，start/end 即区间'),
                (9, 'PROFESSIONAL_SLEEP_WAKE', '睡眠-清醒', '睡眠', '', '一段连续时长，start/end 即区间'),
                (9, 'PROFESSIONAL_SLEEP_NOON', '睡眠-午睡', '睡眠', '', '一段连续时长，start/end 即区间'),
                (500005, 'SLEEP_RECORD', '睡眠记录', '睡眠', '', '每日汇总，评分与任务在 value JSON 内'),
                (500002, 'OBSTRUCTIVE_SLEEP_APNEA', '睡眠呼吸暂停风险', '睡眠', '', '风险等级'),
                (500010, 'SLEEP_ON_OFF_BED_RECORD', '入睡/离床', '睡眠', '', '卧床与起身时刻'),
                (16, 'BLOOD_OXYGEN_SATURATION', '血氧饱和度', '血氧', '%', '单点测量'),
                (400021, 'BLOOD_OXYGEN_SATURATION', '血氧饱和度', '血氧', '%', 'type=16 的平台副本'),
                (19, 'BLOOD_OXYGEN_REMIND', '血氧偏低提醒', '血氧', '%', '低于阈值时触发'),
                (400022, 'BLOOD_OXYGEN_REMIND', '血氧偏低提醒', '血氧', '%', 'type=19 的平台副本'),
                (11, 'STRESS', '压力', '压力与情绪', '', 'type=11 与 500026 是同一批数据，统一写规范键 STRESS'),
                (500026, 'STRESS', '压力', '压力与情绪', '', '与 type=11 重复，导入时去重只留一份'),
                (500031, 'EMOTION', '情绪', '压力与情绪', '', '效价与唤醒度'),
                (500052, 'RELAXATION', '放松训练', '压力与情绪', '', '呼吸训练记录'),
                (8, 'WEIGHT_BODYFAT_BROAD', '体重与体脂', '身体成分', 'kg', '体重、体脂率、BMI 等'),
                (10006, 'WEIGHT_BODYFAT_BROAD', '体重与体脂', '身体成分', 'kg', 'type=8 的平台副本'),
                (500018, 'BASAL_METABOLISM', '基础代谢', '身体成分', 'kcal', '每日一条'),
                (400028, 'VO2MAX', '最大摄氧量', '运动能力', 'ml/kg/min', ''),
                (300011, 'TRAINING_RECOVERY_TIME', '训练恢复时间', '运动能力', '', '恢复状态与恢复完成时刻'),
                (300002, 'SPORT_GOAL_ACHIEVEMENT_DATA', '运动目标达成', '活动量', '', '步数/时长目标'),
                (12, 'EXERCISE_INTENSITY', '中高强度活动', '活动量', 'min', '含运动类型'),
                (200005, 'ACTIVE_HOUR', '活动小时', '活动量', '', '每小时是否活动'),
                (200003, 'ALTITUDE', '海拔', '环境', 'm', '气压计逐分钟')
            """,
            // v5：修正单位口径与静息心率副本。导入代码已改（cal/1000、分米/10、静息心率归一），
            // 但文件指纹没变、增量同步不会重读，所以把已经落库的旧行就地换算一遍。
            // 空库或本来就正确导入的库上，这些 UPDATE 是空操作。
            """
            UPDATE run_activity SET total_calories_kcal = total_calories_kcal / 10.0
             WHERE source_kind = 'huawei' AND total_calories_kcal IS NOT NULL;

            UPDATE run_activity SET descent_meters = descent_meters / 10.0
             WHERE source_kind = 'huawei' AND descent_meters IS NOT NULL;

            UPDATE activity_minute SET calories_kcal = calories_kcal / 10.0
             WHERE calories_kcal IS NOT NULL;

            UPDATE OR IGNORE health_metric SET metric_key = 'RESTING_HEART_RATE'
             WHERE type_code = 7
               AND metric_key IN ('DATA_POINT_REST_HEARTRATE', 'DATA_POINT_NEW_REST_HEARTRATE');

            DELETE FROM health_metric
             WHERE type_code = 7
               AND metric_key IN ('DATA_POINT_REST_HEARTRATE', 'DATA_POINT_NEW_REST_HEARTRATE');

            DELETE FROM health_metric_type
             WHERE type_code = 400022 AND metric_key = 'BLOOD_OXYGEN_REMIND';

            INSERT OR REPLACE INTO health_metric_type (type_code, metric_key, metric_name, category, unit, note) VALUES
                (7, 'RESTING_HEART_RATE', '静息心率', '心率', 'bpm', 'type=7 手机端副本，与 500024 同一批数据，导入时归一'),
                (400022, 'LOW_BLOOD_OXYGEN', '血氧偏低提醒', '血氧', '%', 'type=19 的平台副本，metric_key 与手机端不同')
            """,
            // v6：同一次跑步 TCX 与 JSON 都有时改由 JSON 接管（它是超集，带心率/步频/步数）。
            // 运动 JSON 的指纹清掉，让下一次同步重跑：重合的那几次会走接管分支、
            // 把 TCX 行删掉并把天气改挂到新 id；其他记录只是原地重写一次。
            """
            DELETE FROM source_file WHERE kind = 'huawei' AND source_file LIKE 'motion path detail data%'
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
     *
     * <p>{@code table ... already exists} 同理：v3 迁移里 {@code CREATE TABLE} 出现在
     * {@code ALTER TABLE} 之前，万一在两者之间失败，重跑时建表语句已经成功过。
     * 若不放行，升级会卡在「表已存在」上，只能删库重建——那是不可接受的数据损失。
     */
    private static void execute(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("duplicate column name") || message.contains("already exists")) {
                log.debug("结构已存在，跳过: {}", sql);
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
