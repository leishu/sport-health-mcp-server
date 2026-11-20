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
 * <p>核心表：
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
 *   <li>{@code body_measurement} / {@code sleep_session} / {@code daily_activity} / {@code daily_vitals}：
 *       v7 起由 {@link DerivedTables} 从上面两张原始表重建的业务视图，一次称重 / 一次睡眠 /
 *       一天活动量 / 一天体征各一行。</li>
 *   <li>{@code sample_sequence}：v8 新增，承载华为「Sample sequence data」里的原始样本序列
 *       （HRV RRI、心律失常、睡眠报告、ECG 波形等），一行一条原始记录，detail/extend 原样保留；</li>
 *   <li>{@code excel_sheet} / {@code excel_sheet_row}：v8 新增，把 SportsHealth 的 xls 与
 *       HUAWEI_Research 的 xlsx 按「工作表 + 行」原样落库，空单元格不占空间，表头另存；</li>
 *   <li>{@code track_stream}：v8 新增，运动 detailData 里未进 {@code track_point} 的流
 *       （每 5 秒速度、跑姿、单公里配速、事件等），一行一个采样；</li>
 *   <li>{@code sleep_report} / {@code ecg_record} / {@code daily_sport_stat} /
 *       {@code sport_daily_dimen_stat} / {@code daily_health_stat} /
 *       {@code daily_health_samplestat} / {@code run_level} / {@code medal}：
 *       v8 起由 {@link DerivedTables} 从样本序列与 Excel 原始行重建的便捷表。</li>
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
    static final int SCHEMA_VERSION = 10;

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
            """,
            // v7：体重、睡眠、每日活动、每日体征各落成一张直接可查的表。
            // 这里只建表，数据由 DerivedTables 在每次同步末尾用 INSERT … SELECT 重建：
            // 升级上来的旧库在下一次同步时一次性补齐，不需要重导 630MB 源文件；
            // 新建的空库则等到第一次真正有数据时才会填满。
            """
            CREATE TABLE IF NOT EXISTS body_measurement (
                record_id             TEXT PRIMARY KEY,
                measured_at           TEXT NOT NULL,
                weight_kg             REAL NOT NULL,
                bmi                   REAL,
                body_fat_percent      REAL,
                body_fat_kg           REAL,
                muscle_mass_kg        REAL,
                protein_percent       REAL,
                body_water_percent    REAL,
                bone_salt_kg          REAL,
                visceral_fat_level    REAL,
                waist_hip_ratio       REAL,
                basal_metabolism_kcal REAL,
                body_age              INTEGER,
                body_score            REAL,
                height_cm             REAL,
                heart_rate            INTEGER,
                gender                INTEGER,
                has_body_composition  INTEGER NOT NULL,
                source_file           TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_body_measurement_at ON body_measurement (measured_at);

            CREATE TABLE IF NOT EXISTS sleep_session (
                session_start  TEXT PRIMARY KEY,
                session_end    TEXT NOT NULL,
                sleep_date     TEXT NOT NULL,
                sleep_type     TEXT NOT NULL,
                in_bed_minutes INTEGER NOT NULL,
                asleep_minutes INTEGER NOT NULL,
                deep_minutes   INTEGER NOT NULL,
                light_minutes  INTEGER NOT NULL,
                rem_minutes    INTEGER NOT NULL,
                awake_minutes  INTEGER NOT NULL,
                noon_minutes   INTEGER NOT NULL,
                segment_count  INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_sleep_session_date ON sleep_session (sleep_date, sleep_type);

            CREATE TABLE IF NOT EXISTS daily_activity (
                activity_date   TEXT PRIMARY KEY,
                steps           INTEGER NOT NULL,
                walk_steps      INTEGER NOT NULL,
                run_steps       INTEGER NOT NULL,
                distance_meters REAL NOT NULL,
                calories_kcal   REAL NOT NULL,
                active_minutes  INTEGER NOT NULL,
                stand_minutes   INTEGER NOT NULL,
                first_minute    TEXT,
                last_minute     TEXT,
                minute_rows     INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS daily_vitals (
                heart_date         TEXT PRIMARY KEY,
                resting_heart_rate INTEGER,
                hrv_rmssd          REAL,
                avg_heart_rate     REAL,
                min_heart_rate     INTEGER,
                max_heart_rate     INTEGER,
                heart_rate_samples INTEGER NOT NULL,
                stress_avg         REAL,
                stress_max         INTEGER,
                vo2max             REAL
            )
            """,
            // v8：把此前忽略的三类原始数据也收进库。
            //   - Sample sequence data：13 个 JSON、1.18 万条记录，detailData 常有几十万字符的波形；
            //   - SportsHealth xls：93 个数据文件里 37 个非空，按工作表 + 行原样保存；
            //   - HUAWEI_Research xlsx：4 张汇总表；
            //   - 运动 detailData 里未入 track_point 的流，以及 summaryData 全文。
            // 运动 JSON 的指纹在这里清掉，让下一次同步重读一遍补齐 summary_data / track_stream；
            // sample sequence 与 Excel 是新增类别，本身没有旧指纹，会自然首次导入。
            """
            CREATE TABLE IF NOT EXISTS sample_sequence (
                record_id    TEXT PRIMARY KEY,
                type_code    INTEGER NOT NULL,
                data_id      INTEGER,
                sub_type     INTEGER,
                start_time   TEXT NOT NULL,
                end_time     TEXT NOT NULL,
                time_zone    TEXT,
                device_code  TEXT,
                summary_data TEXT,
                detail_data  TEXT,
                extend_data  TEXT,
                source_file  TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_sample_sequence_type
                ON sample_sequence (type_code, start_time);
            CREATE INDEX IF NOT EXISTS idx_sample_sequence_source
                ON sample_sequence (source_file);

            CREATE TABLE IF NOT EXISTS excel_sheet (
                source_file  TEXT NOT NULL,
                sheet_name   TEXT NOT NULL,
                row_count    INTEGER NOT NULL,
                column_count INTEGER NOT NULL,
                header_json  TEXT NOT NULL,
                PRIMARY KEY (source_file, sheet_name)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS excel_sheet_row (
                source_file TEXT NOT NULL,
                sheet_name  TEXT NOT NULL,
                row_number  INTEGER NOT NULL,
                row_data    TEXT NOT NULL,
                PRIMARY KEY (source_file, sheet_name, row_number)
            ) WITHOUT ROWID;

            CREATE INDEX IF NOT EXISTS idx_excel_sheet_row_sheet
                ON excel_sheet_row (sheet_name, row_number);

            CREATE TABLE IF NOT EXISTS track_stream (
                run_id       TEXT NOT NULL,
                stream_type  TEXT NOT NULL,
                seq          INTEGER NOT NULL,
                time_millis  INTEGER,
                point_index  INTEGER,
                value        REAL,
                data         TEXT NOT NULL,
                PRIMARY KEY (run_id, stream_type, seq)
            ) WITHOUT ROWID;

            ALTER TABLE run_activity ADD COLUMN summary_data TEXT;

            CREATE TABLE IF NOT EXISTS sleep_report (
                record_id                       TEXT PRIMARY KEY,
                start_time                      TEXT NOT NULL,
                end_time                        TEXT NOT NULL,
                sleep_date                      TEXT NOT NULL,
                sleep_score                     REAL,
                sleep_efficiency                REAL,
                sleep_latency                   REAL,
                avg_breathrate                  REAL,
                avg_heartrate                   REAL,
                avg_hrv                         REAL,
                avg_oxygen_saturation           REAL,
                rdi                             REAL,
                snore_freq                      REAL,
                wake_count                      INTEGER,
                turn_over_count                 INTEGER,
                deep_part                       REAL,
                bed_time                        INTEGER,
                fall_asleep_time                INTEGER,
                rising_time                     INTEGER,
                wakeup_time                     INTEGER,
                prepare_sleep_time              INTEGER,
                sleep_data_quality              INTEGER,
                valid_data                      REAL,
                breathrate_day_to_baseline      REAL,
                heartrate_day_to_baseline       REAL,
                hrv_day_to_baseline             REAL,
                oxygen_saturation_day_to_baseline REAL
            );

            CREATE INDEX IF NOT EXISTS idx_sleep_report_date ON sleep_report (sleep_date);

            CREATE TABLE IF NOT EXISTS ecg_record (
                record_id             TEXT PRIMARY KEY,
                start_time            TEXT NOT NULL,
                end_time              TEXT NOT NULL,
                average_heart_rate    INTEGER,
                ecg_arrhy_type        INTEGER,
                ecg_data_length       INTEGER,
                data_sources          TEXT,
                ecg_app_version       TEXT,
                package_name          TEXT,
                user_symptom          INTEGER,
                device_code           TEXT,
                detail_sample_count   INTEGER,
                extend_sample_count   INTEGER
            );

            CREATE TABLE IF NOT EXISTS daily_sport_stat (
                activity_date    TEXT PRIMARY KEY,
                excel_row        INTEGER NOT NULL,
                data_id          TEXT,
                time_zone        TEXT,
                data_source      TEXT,
                device_code      TEXT,
                sport_type       TEXT,
                sport_basic_info TEXT,
                steps            INTEGER,
                distance_meters  REAL,
                calories_kcal    REAL,
                altitude         REAL,
                floors           REAL,
                duration_seconds REAL,
                record_count     INTEGER,
                version          TEXT
            );

            CREATE TABLE IF NOT EXISTS sport_daily_dimen_stat (
                activity_date    TEXT NOT NULL,
                sport_type       TEXT NOT NULL,
                excel_row        INTEGER NOT NULL,
                data_id          TEXT,
                time_zone        TEXT,
                data_source      TEXT,
                device_code      TEXT,
                sport_basic_info TEXT,
                steps            INTEGER,
                distance_meters  REAL,
                calories_kcal    REAL,
                altitude         REAL,
                floors           REAL,
                duration_seconds REAL,
                record_count     INTEGER,
                version          TEXT,
                PRIMARY KEY (activity_date, sport_type, excel_row)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS daily_health_stat (
                generate_time TEXT NOT NULL,
                type_code     TEXT NOT NULL,
                excel_row     INTEGER NOT NULL,
                time_zone     TEXT,
                total_info    TEXT NOT NULL,
                PRIMARY KEY (generate_time, type_code, excel_row)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS daily_health_samplestat (
                generate_time TEXT NOT NULL,
                type_code     TEXT NOT NULL,
                excel_row     INTEGER NOT NULL,
                time_zone     TEXT,
                total_info    TEXT NOT NULL,
                PRIMARY KEY (generate_time, type_code, excel_row)
            ) WITHOUT ROWID;

            CREATE TABLE IF NOT EXISTS run_level (
                record_day             TEXT PRIMARY KEY,
                excel_row              INTEGER NOT NULL,
                version                TEXT,
                current_run_level      REAL,
                a_speed                REAL,
                e_speed                REAL,
                i_speed                REAL,
                m_speed                REAL,
                r_speed                REAL,
                km1_seconds            REAL,
                km3_seconds            REAL,
                km5_seconds            REAL,
                km10_seconds           REAL,
                half_marathon_seconds  REAL,
                marathon_seconds       REAL,
                user_run_level_json    TEXT,
                motion_path_run_level_json TEXT
            );

            CREATE TABLE IF NOT EXISTS medal (
                medal_type  TEXT NOT NULL,
                medal_level TEXT NOT NULL,
                excel_row   INTEGER NOT NULL,
                gain_count  INTEGER,
                take_date   TEXT,
                modify_time TEXT,
                PRIMARY KEY (medal_type, medal_level, excel_row)
            ) WITHOUT ROWID;

            -- 派生表都可以从原始表重算。清空一次，保证升级到 v8 后即使没有任何文件重导，
            -- 下一次同步的「派生表为空」判断也会触发一次全量重建，把新增的 8 张表填满。
            DELETE FROM body_measurement;
            DELETE FROM sleep_session;
            DELETE FROM daily_activity;
            DELETE FROM daily_vitals;

            DELETE FROM source_file
             WHERE kind = 'huawei' AND source_file LIKE 'motion path detail data%'
            """,
            """
            -- v9：daily_activity 的「站立」换成事件条数。
            -- 原来的 stand_minutes 求的是 is_stand=1 的分钟数之和，可华为把站立记成
            -- sportType=13 的整点事件（duration 恒为 0），COALESCE(duration_minutes,1) 于是
            -- 一分也算不到 —— 全库 368 天里 stand_minutes 最大只有 8、均值 2.0，
            -- 而同一份数据里 sport_type=13 每天 3~22 条。华为的站立目标是「12 次/小时」，
            -- 所以换成 stand_events = 当天站立事件条数，量纲才对得上。
            ALTER TABLE daily_activity DROP COLUMN stand_minutes;
            ALTER TABLE daily_activity ADD COLUMN stand_events INTEGER NOT NULL DEFAULT 0;
            UPDATE daily_activity
               SET stand_events = COALESCE((
                       SELECT COUNT(*)
                         FROM activity_minute m
                        WHERE m.sport_type = 13
                          AND substr(CAST(m.record_day AS TEXT), 1, 4) || '-' ||
                              substr(CAST(m.record_day AS TEXT), 5, 2) || '-' ||
                              substr(CAST(m.record_day AS TEXT), 7, 2) = daily_activity.activity_date), 0);
            """,
            """
            -- v10：daily_activity 改成「同一分钟只算一次」。
            -- activity_minute 的主键是 (start_time, sport_type)，看起来一分钟一行，实际全库有
            -- 2 208 个分钟存了两条 —— 无一例外是两台设备同时记的同一分钟（第三台设备还把走路
            -- 报成 sport_type=2、另两台报 5）。逐行相加让步数虚高 3.45%、距离与热量虚高约 3%，
            -- 2026-06-14 那天甚至多算 5 080 步（20%）。
            -- 派生表整表重算最省事：清空后按去重口径重插，SQL 直接复用 DerivedTables 里的常量，
            -- 避免两处口径漂移。回填必须写在这里 —— DerivedTables.rebuild() 只在「重导过文件」
            -- 或「表为空」时触发，升级后不重导文件就不会重建。
            DELETE FROM daily_activity;
            """ + DerivedTables.DAILY_ACTIVITY_SQL);

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
     *
     * <p>{@code no such column} 是 v9 的 {@code DROP COLUMN} 需要的：那条迁移先删列再建列，
     * 若删列成功后紧接着失败，重跑时列已经不存在，不该因此把整个升级卡死。
     */
    private static void execute(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("duplicate column name") || message.contains("already exists")
                    || message.contains("no such column")) {
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
