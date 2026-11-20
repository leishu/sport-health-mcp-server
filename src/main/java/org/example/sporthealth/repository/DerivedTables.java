package org.example.sporthealth.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 把华为导出的原始长表整理成四张按业务对象命名的派生表。
 *
 * <p>{@code health_metric} 是「一行一个指标值」的窄表：心率、睡眠分期、体重、压力全挤在里面，
 * 数值要么在 {@code value_num}、要么在 {@code value_text} 的 JSON 里，直接查要写一堆
 * {@code metric_key = ...} 与 {@code json_extract}。{@code activity_minute} 同理，一行一分钟。
 * 四类数据各自最常见的看法都不一样，所以在这里落成四张表：
 *
 * <ul>
 *   <li>{@link #BODY_MEASUREMENT_SQL 体重与体成分}：一次称重一行，把体重 JSON 拆成列；</li>
 *   <li>{@link #SLEEP_SESSION_SQL 睡眠}：一次睡眠（含午睡）一行，把逐分钟分期合并成阶段时长；</li>
 *   <li>{@link #DAILY_ACTIVITY_SQL 每日活动量}：一天一行，步数 / 距离 / 热量 / 中高强度 / 站立；</li>
 *   <li>{@link #DAILY_VITALS_SQL 每日体征}：一天一行，静息心率 / HRV / 动态心率区间 / 压力 / 最大摄氧量。</li>
 * </ul>
 *
 * <p>为什么是「派生」而不是「导入时顺便写」：这四张表的内容完全由 {@code health_metric} 与
 * {@code activity_minute} 决定，不含任何原始文件里的新信息。用 INSERT … SELECT 重建，
 * 既能给历史库一次性补齐（结构升级到 v7 后第一次同步即回填），也不需要在解析器里多写四条链路。
 * 代价是重建要重算一遍：四张表合计不到两千行，但睡眠会话要在 17.8 万条分期上跑窗口函数，
 * 首次冷缓存实测约 5 秒，页面缓存命中的重复运行在 1 秒内。因此调用方只在「真的重导过文件」
 * 或「表是空的」时才重建，日常的空转同步不会白算。
 *
 * <p>重建在**导入事务之外**的独立事务里：先清空再整表重插，中途失败整体回滚，
 * 不会留下「清了一半没插回去」的空表。
 */
@Component
@Slf4j
public class DerivedTables {

    /** 重建后的行数，供 {@code describe_data_source} 与日志展示。 */
    public record Counts(int bodyMeasurements,
                         int sleepSessions,
                         int activityDays,
                         int vitalDays,
                         int sleepReports,
                         int ecgRecords,
                         int dailySportStats,
                         int sportDimenStats,
                         int dailyHealthStats,
                         int dailyHealthSamplestats,
                         int runLevels,
                         int medals) {

        public static Counts empty() {
            return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 一次称重一行。
     *
     * <p>同一台体脂秤导出的 JSON 有两种形态：只有 {@code bodyWeight}（普通体重秤模式，
     * 166/308 条）和带全套体成分（BMI、体脂率、骨骼肌量……）。后者用
     * {@code has_body_composition = 1} 标出来，统计体脂趋势时按它过滤即可。
     *
     * <p>注意 {@code basalMetabolism} 在这个 JSON 里已经是千卡（1764.0），
     * 与 {@code health_metric} 里 type=500018 的「卡」（2182000）不是同一个口径，不能混用。
     */
    private static final String BODY_MEASUREMENT_SQL = """
            INSERT INTO body_measurement (
                record_id, measured_at, weight_kg, bmi, body_fat_percent, body_fat_kg,
                muscle_mass_kg, protein_percent, body_water_percent, bone_salt_kg,
                visceral_fat_level, waist_hip_ratio, basal_metabolism_kcal,
                body_age, body_score, height_cm, heart_rate, gender,
                has_body_composition, source_file
            )
            SELECT record_id,
                   substr(start_time, 1, 19)                             AS measured_at,
                   json_extract(value_text, '$.bodyWeight')              AS weight_kg,
                   json_extract(value_text, '$.bmi')                     AS bmi,
                   json_extract(value_text, '$.bodyFatRate')             AS body_fat_percent,
                   json_extract(value_text, '$.bodyFat')                 AS body_fat_kg,
                   json_extract(value_text, '$.skeletalMusclelMass')     AS muscle_mass_kg,
                   json_extract(value_text, '$.proteinRate')             AS protein_percent,
                   json_extract(value_text, '$.moistureRate')            AS body_water_percent,
                   json_extract(value_text, '$.boneSalt')                AS bone_salt_kg,
                   json_extract(value_text, '$.visceralFatLevel')        AS visceral_fat_level,
                   json_extract(value_text, '$.waistHipRatio')           AS waist_hip_ratio,
                   json_extract(value_text, '$.basalMetabolism')         AS basal_metabolism_kcal,
                   json_extract(value_text, '$.bodyAge')                 AS body_age,
                   json_extract(value_text, '$.bodyScore')               AS body_score,
                   json_extract(value_text, '$.height')                  AS height_cm,
                   json_extract(value_text, '$.heartRate')               AS heart_rate,
                   json_extract(value_text, '$.gender')                  AS gender,
                   CASE WHEN json_extract(value_text, '$.bmi') IS NULL THEN 0 ELSE 1 END
                                                                         AS has_body_composition,
                   source_file
            FROM health_metric
            WHERE metric_key = 'WEIGHT_BODYFAT_BROAD'
              AND json_valid(value_text)
              AND json_extract(value_text, '$.bodyWeight') IS NOT NULL
            """;

    /**
     * 一次睡眠（含午睡）一行。
     *
     * <p>源数据是逐分钟的分期（深睡/浅睡/快速眼动/清醒/午睡），一行只有一分钟。
     * 这里用「相邻两段间隔超过 180 分钟就算另一次睡眠」把碎片粘成会话：正常夜里最深的一段空白
     * 是中途醒来（实测最长 90 分钟），而午睡与夜睡之间至少隔几个小时，180 分钟能干净地切开。
     *
     * <p>{@code sleep_date} 取**醒来那天**而不是入睡那天：23:20 睡、07:00 起算作次日，
     * 与华为 App 的「昨晚睡了多久」一致，也方便和当天的步数、静息心率对齐。
     *
     * <p>{@code NAP} 的判断只看这段会话里有没有午睡分期——华为把午睡单独记成
     * {@code PROFESSIONAL_SLEEP_NOON}，不会混进夜睡的分期里。{@code asleep_minutes} 把午睡
     * 也算进睡眠时长：午睡那一场只有 NOON 分期，若只加 deep+light+rem 会得到 0 分钟。
     */
    private static final String SLEEP_SESSION_SQL = """
            INSERT INTO sleep_session (
                session_start, session_end, sleep_date, sleep_type, in_bed_minutes,
                asleep_minutes, deep_minutes, light_minutes, rem_minutes,
                awake_minutes, noon_minutes, segment_count
            )
            WITH seg AS (
                SELECT metric_key,
                       julianday(start_time) AS s,
                       julianday(end_time)   AS e
                FROM health_metric
                WHERE metric_key LIKE 'PROFESSIONAL_SLEEP_%'
            ), ordered AS (
                SELECT metric_key, s, e, LAG(e) OVER (ORDER BY s) AS prev_end
                FROM seg
            ), flagged AS (
                SELECT metric_key, s, e,
                       CASE WHEN prev_end IS NULL OR (s - prev_end) * 1440.0 > 180 THEN 1 ELSE 0 END AS is_new
                FROM ordered
            ), numbered AS (
                SELECT metric_key, s, e,
                       SUM(is_new) OVER (ORDER BY s ROWS UNBOUNDED PRECEDING) AS session_no
                FROM flagged
            ), sessions AS (
                SELECT MIN(datetime(s)) AS session_start,
                       MAX(datetime(e)) AS session_end,
                       ROUND((MAX(e) - MIN(s)) * 1440.0) AS in_bed,
                       ROUND(SUM(CASE WHEN metric_key = 'PROFESSIONAL_SLEEP_DEEP'    THEN (e - s) * 1440.0 ELSE 0 END)) AS deep,
                       ROUND(SUM(CASE WHEN metric_key = 'PROFESSIONAL_SLEEP_SHALLOW' THEN (e - s) * 1440.0 ELSE 0 END)) AS light,
                       ROUND(SUM(CASE WHEN metric_key = 'PROFESSIONAL_SLEEP_DREAM'   THEN (e - s) * 1440.0 ELSE 0 END)) AS rem,
                       ROUND(SUM(CASE WHEN metric_key = 'PROFESSIONAL_SLEEP_WAKE'    THEN (e - s) * 1440.0 ELSE 0 END)) AS awake,
                       ROUND(SUM(CASE WHEN metric_key = 'PROFESSIONAL_SLEEP_NOON'    THEN (e - s) * 1440.0 ELSE 0 END)) AS noon,
                       COUNT(*) AS segments
                FROM numbered
                GROUP BY session_no
            )
            SELECT session_start,
                   session_end,
                   date(session_end)                         AS sleep_date,
                   CASE WHEN noon > 0 THEN 'NAP' ELSE 'NIGHT' END AS sleep_type,
                   in_bed,
                   deep + light + rem + noon                 AS asleep_minutes,
                   deep, light, rem, awake, noon, segments
            FROM sessions
            WHERE in_bed > 0
            """;

    /**
     * 一天一行，来自「每分钟运动数据」。
     *
     * <p>{@code record_day} 在导出里是整数（20261109），这里还原成 {@code YYYY-MM-DD}，
     * 好和 {@code run_activity.run_date}、{@code daily_vitals.heart_date} 直接 join。
     *
     * <p><b>按去重后的分钟汇总</b>（{@link ActivityMinuteStore#MERGED_MINUTE_SQL}）：同一分钟被两台
     * 设备各记一条时只算一次。v10 之前是直接把 {@code activity_minute} 的每一行相加，
     * 2 208 个「一分钟两条」的分钟因此被算了双份，全库步数虚高 3.45%、距离与热量虚高约 3%，
     * 个别日子（2026-06-14）甚至虚高 20%。站立事件不参与去重，见 {@code stand_events}。
     *
     * <p>{@code walk_steps} 把 {@code sportType} 2 和 5 都算作走路：第三台设备把走路报成 2，
     * 另两台报 5，只认 5 会让换设备之后的走路步数凭空消失（全库 14.4 万步）。
     *
     * <p>{@code stand_events} 数的是 {@code sportType=13} 的记录条数，不是时长：
     * 华为把站立记成整点事件（{@code duration=0}），一天 3~22 条，对应 App 里的「站立 N/12 次」。
     * 早期版本在这里求 {@code is_stand=1} 的分钟数之和，因为时长恒为 0，得到的其实是
     * 「走路分钟里被标了站立的那些」，全库均值只有 2.0 —— v9 已改成事件条数。
     */
    static final String DAILY_ACTIVITY_SQL = """
            INSERT INTO daily_activity (
                activity_date, steps, walk_steps, run_steps, distance_meters, calories_kcal,
                active_minutes, stand_events, first_minute, last_minute, minute_rows
            )
            WITH merged AS (%s)
            SELECT substr(CAST(record_day AS TEXT), 1, 4) || '-' ||
                   substr(CAST(record_day AS TEXT), 5, 2) || '-' ||
                   substr(CAST(record_day AS TEXT), 7, 2)                       AS activity_date,
                   COALESCE(SUM(steps), 0)                                             AS steps,
                   COALESCE(SUM(CASE WHEN sport_type IN (2, 5) THEN steps ELSE 0 END), 0)
                                                                                       AS walk_steps,
                   COALESCE(SUM(CASE WHEN sport_type = 4 THEN steps ELSE 0 END), 0)    AS run_steps,
                   ROUND(COALESCE(SUM(distance_meters), 0), 1)                         AS distance_meters,
                   ROUND(COALESCE(SUM(calories_kcal), 0), 1)                           AS calories_kcal,
                   COALESCE(SUM(CASE WHEN is_intensity = 1 THEN COALESCE(duration_minutes, 1) ELSE 0 END), 0)
                                                                                       AS active_minutes,
                   COALESCE(SUM(CASE WHEN sport_type = 13 THEN 1 ELSE 0 END), 0)       AS stand_events,
                   MIN(start_time)                                                     AS first_minute,
                   MAX(end_time)                                                       AS last_minute,
                   COUNT(*)                                                            AS minute_rows
            FROM merged
            GROUP BY record_day
            """.formatted(ActivityMinuteStore.MERGED_MINUTE_SQL);

    /**
     * 一天一行的心率与相关体征。
     *
     * <p>{@code DYNAMIC_HEART_RATE} 是逐分钟自动采样（一天 200~320 条），所以「最低/最高心率」
     * 是采样区间而不是真实极值——睡眠时段的 39 bpm 与运动时的 142 bpm 都在里面。
     *
     * <p>静息心率一天有 2~6 条（手机端与手表各报一份，归一后仍是不同 recordId），
     * 取**当天最小值**作为当日静息心率：华为口径的静息心率就是「睡眠期最低的稳定心率」，
     * 取最小值与 App 展示的数值基本一致。
     */
    private static final String DAILY_VITALS_SQL = """
            INSERT INTO daily_vitals (
                heart_date, resting_heart_rate, hrv_rmssd, avg_heart_rate,
                min_heart_rate, max_heart_rate, heart_rate_samples,
                stress_avg, stress_max, vo2max
            )
            SELECT substr(start_time, 1, 10)                                            AS heart_date,
                   CAST(MIN(CASE WHEN metric_key = 'RESTING_HEART_RATE' THEN value_num END) AS INTEGER)
                                                                                        AS resting_heart_rate,
                   ROUND(AVG(CASE WHEN metric_key = 'HEART_RATE_VARIABILITY' THEN value_num END), 1)
                                                                                        AS hrv_rmssd,
                   ROUND(AVG(CASE WHEN metric_key = 'DYNAMIC_HEART_RATE' THEN value_num END), 1)
                                                                                        AS avg_heart_rate,
                   CAST(MIN(CASE WHEN metric_key = 'DYNAMIC_HEART_RATE' THEN value_num END) AS INTEGER)
                                                                                        AS min_heart_rate,
                   CAST(MAX(CASE WHEN metric_key = 'DYNAMIC_HEART_RATE' THEN value_num END) AS INTEGER)
                                                                                        AS max_heart_rate,
                   SUM(CASE WHEN metric_key = 'DYNAMIC_HEART_RATE' THEN 1 ELSE 0 END) AS heart_rate_samples,
                   ROUND(AVG(CASE WHEN metric_key = 'STRESS' THEN value_num END), 1)    AS stress_avg,
                   CAST(MAX(CASE WHEN metric_key = 'STRESS' THEN value_num END) AS INTEGER)
                                                                                        AS stress_max,
                   MAX(CASE WHEN metric_key = 'VO2MAX' THEN value_num END)              AS vo2max
            FROM health_metric
            WHERE metric_key IN ('RESTING_HEART_RATE', 'HEART_RATE_VARIABILITY',
                                 'DYNAMIC_HEART_RATE', 'STRESS', 'VO2MAX')
              AND length(start_time) >= 10
            GROUP BY substr(start_time, 1, 10)
            """;

    /**
     * 夜间睡眠报告（type=700013）。
     *
     * <p>数据源是 {@code sample_sequence.summary_data} 里的一段 JSON。这里只挑可分析的字段，
     * 原始 JSON 仍在 {@code sample_sequence} 里，不会被派生表覆盖或丢弃。
     */
    private static final String SLEEP_REPORT_SQL = """
            INSERT INTO sleep_report (
                record_id, start_time, end_time, sleep_date, sleep_score, sleep_efficiency,
                sleep_latency, avg_breathrate, avg_heartrate, avg_hrv, avg_oxygen_saturation,
                rdi, snore_freq, wake_count, turn_over_count, deep_part, bed_time,
                fall_asleep_time, rising_time, wakeup_time, prepare_sleep_time,
                sleep_data_quality, valid_data, breathrate_day_to_baseline,
                heartrate_day_to_baseline, hrv_day_to_baseline,
                oxygen_saturation_day_to_baseline
            )
            SELECT record_id,
                   start_time,
                   end_time,
                   substr(end_time, 1, 10),
                   json_extract(summary_data, '$.sleepScore'),
                   json_extract(summary_data, '$.sleepEfficiency'),
                   json_extract(summary_data, '$.sleepLatency'),
                   json_extract(summary_data, '$.avgBreathrate'),
                   json_extract(summary_data, '$.avgHeartrate'),
                   json_extract(summary_data, '$.avgHrv'),
                   json_extract(summary_data, '$.avgOxygenSaturation'),
                   json_extract(summary_data, '$.rdi'),
                   json_extract(summary_data, '$.snoreFreq'),
                   json_extract(summary_data, '$.wakeCount'),
                   json_extract(summary_data, '$.turnOverCount'),
                   json_extract(summary_data, '$.deepPart'),
                   json_extract(summary_data, '$.bedTime'),
                   json_extract(summary_data, '$.fallAsleepTime'),
                   json_extract(summary_data, '$.risingTime'),
                   json_extract(summary_data, '$.wakeupTime'),
                   json_extract(summary_data, '$.prepareSleepTime'),
                   json_extract(summary_data, '$.sleepDataQuality'),
                   json_extract(summary_data, '$.validData'),
                   json_extract(summary_data, '$.breathrateDayToBaseline'),
                   json_extract(summary_data, '$.heartrateDayToBaseline'),
                   json_extract(summary_data, '$.hrvDayToBaseline'),
                   json_extract(summary_data, '$.oxygenSaturationDayToBaseline')
            FROM sample_sequence
            WHERE type_code = 700013
              AND json_valid(summary_data)
            """;

    /** ECG 记录（type=700009）：元数据拆列，波形仍留在 sample_sequence 的原文里。 */
    private static final String ECG_RECORD_SQL = """
            INSERT INTO ecg_record (
                record_id, start_time, end_time, average_heart_rate, ecg_arrhy_type,
                ecg_data_length, data_sources, ecg_app_version, package_name, user_symptom,
                device_code, detail_sample_count, extend_sample_count
            )
            SELECT record_id,
                   start_time,
                   end_time,
                   json_extract(summary_data, '$.averageHeartRate'),
                   json_extract(summary_data, '$.ecgArrhyType'),
                   json_extract(summary_data, '$.ecgDataLength'),
                   json_extract(summary_data, '$.dataSources'),
                   json_extract(summary_data, '$.ecgAppVersion'),
                   json_extract(summary_data, '$.packageName'),
                   json_extract(summary_data, '$.userSymptom'),
                   device_code,
                   CASE WHEN json_valid(detail_data) THEN json_array_length(detail_data) END,
                   CASE WHEN json_valid(extend_data) THEN json_array_length(extend_data) END
            FROM sample_sequence
            WHERE type_code = 700009
            """;

    /**
     * 每日运动统计（{@code user sport daily statistics.xls}）。
     *
     * <p>这份导出里数值列大量为空，但日期与设备列是完整的；空单元格在导入时没有写进
     * {@code row_data}，所以这里统一用 {@code json_extract} 取，缺了就是 NULL。
     */
    private static final String DAILY_SPORT_STAT_SQL = """
            INSERT INTO daily_sport_stat (
                activity_date, excel_row, data_id, time_zone, data_source, device_code,
                sport_type, sport_basic_info, steps, distance_meters, calories_kcal,
                altitude, floors, duration_seconds, record_count, version
            )
            SELECT json_extract(row_data, '$.recordDay'),
                   row_number,
                   json_extract(row_data, '$.dataId'),
                   json_extract(row_data, '$.timeZone'),
                   json_extract(row_data, '$.dataSource'),
                   json_extract(row_data, '$.deviceCode'),
                   json_extract(row_data, '$.sportType'),
                   json_extract(row_data, '$.sportBasicInfo'),
                   CAST(NULLIF(json_extract(row_data, '$.steps'), '') AS INTEGER),
                   CAST(NULLIF(json_extract(row_data, '$.distance'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.calorie'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.altitude'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.floor'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.duration'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.count'), '') AS INTEGER),
                   json_extract(row_data, '$.version')
            FROM excel_sheet_row
            WHERE sheet_name = 'user sport daily statistics'
              AND json_extract(row_data, '$.recordDay') IS NOT NULL
            """;

    /** 按运动类型拆分的每日统计（{@code sport daily dimen statistics.xls}）。 */
    private static final String SPORT_DIMEN_STAT_SQL = """
            INSERT INTO sport_daily_dimen_stat (
                activity_date, sport_type, excel_row, data_id, time_zone, data_source,
                device_code, sport_basic_info, steps, distance_meters, calories_kcal,
                altitude, floors, duration_seconds, record_count, version
            )
            SELECT json_extract(row_data, '$.recordDay'),
                   json_extract(row_data, '$.sportType'),
                   row_number,
                   json_extract(row_data, '$.dataId'),
                   json_extract(row_data, '$.timeZone'),
                   json_extract(row_data, '$.dataSource'),
                   json_extract(row_data, '$.deviceCode'),
                   json_extract(row_data, '$.sportBasicInfo'),
                   CAST(NULLIF(json_extract(row_data, '$.steps'), '') AS INTEGER),
                   CAST(NULLIF(json_extract(row_data, '$.distance'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.calorie'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.altitude'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.floor'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.duration'), '') AS REAL),
                   CAST(NULLIF(json_extract(row_data, '$.count'), '') AS INTEGER),
                   json_extract(row_data, '$.version')
            FROM excel_sheet_row
            WHERE sheet_name = 'sport daily dimen statistics'
              AND json_extract(row_data, '$.recordDay') IS NOT NULL
              AND json_extract(row_data, '$.sportType') IS NOT NULL
            """;

    /** 每日健康统计（{@code user health daily statistics.xls}），totalInfo 按 type 不同而不同。 */
    private static final String DAILY_HEALTH_STAT_SQL = """
            INSERT INTO daily_health_stat (generate_time, type_code, excel_row, time_zone, total_info)
            SELECT json_extract(row_data, '$.generateTime'),
                   json_extract(row_data, '$.type'),
                   row_number,
                   json_extract(row_data, '$.timeZone'),
                   json_extract(row_data, '$.totalInfo')
            FROM excel_sheet_row
            WHERE sheet_name = 'user health daily statistics'
              AND json_extract(row_data, '$.generateTime') IS NOT NULL
              AND json_extract(row_data, '$.type') IS NOT NULL
              AND json_extract(row_data, '$.totalInfo') IS NOT NULL
            """;

    /** 每日健康样本统计（{@code user health daily samplestat.xls}）。 */
    private static final String DAILY_HEALTH_SAMPLESTAT_SQL = """
            INSERT INTO daily_health_samplestat (generate_time, type_code, excel_row, time_zone, total_info)
            SELECT json_extract(row_data, '$.generateTime'),
                   json_extract(row_data, '$.type'),
                   row_number,
                   json_extract(row_data, '$.timeZone'),
                   json_extract(row_data, '$.totalInfo')
            FROM excel_sheet_row
            WHERE sheet_name = 'user health daily samplestat'
              AND json_extract(row_data, '$.generateTime') IS NOT NULL
              AND json_extract(row_data, '$.type') IS NOT NULL
              AND json_extract(row_data, '$.totalInfo') IS NOT NULL
            """;

    /** 华为官方跑力（{@code user run level data.xls}）：把第一个数组元素里的常用值拆成列。 */
    private static final String RUN_LEVEL_SQL = """
            INSERT INTO run_level (
                record_day, excel_row, version, current_run_level, a_speed, e_speed,
                i_speed, m_speed, r_speed, km1_seconds, km3_seconds, km5_seconds,
                km10_seconds, half_marathon_seconds, marathon_seconds,
                user_run_level_json, motion_path_run_level_json
            )
            SELECT record_day, excel_row, version,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].currentRunLevel') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].aSpeed') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].eSpeed') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].iSpeed') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].mSpeed') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].rSpeed') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].km1') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].km3') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].km5') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].km10') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].halfMarathon') END,
                   CASE WHEN json_valid(user_json)
                        THEN json_extract(user_json, '$[0].marathon') END,
                   user_json,
                   motion_json
            FROM (SELECT json_extract(row_data, '$.recordDay') AS record_day,
                         row_number                                   AS excel_row,
                         json_extract(row_data, '$.version')          AS version,
                         json_extract(row_data, '$.userRunLevel')     AS user_json,
                         json_extract(row_data, '$.motionPathRunLevel') AS motion_json
                  FROM excel_sheet_row
                  WHERE sheet_name = 'user run level data'
                    AND json_extract(row_data, '$.recordDay') IS NOT NULL)
            """;

    /** 勋章（{@code user medal info.xls}）。同一类型/等级可能多次获得，用 excel_row 区分。 */
    private static final String MEDAL_SQL = """
            INSERT INTO medal (medal_type, medal_level, excel_row, gain_count, take_date, modify_time)
            SELECT json_extract(row_data, '$.medalType'),
                   json_extract(row_data, '$.medalLevel'),
                   row_number,
                   CAST(NULLIF(json_extract(row_data, '$.gainCount'), '') AS INTEGER),
                   json_extract(row_data, '$.takeDate'),
                   json_extract(row_data, '$.modifyTime')
            FROM excel_sheet_row
            WHERE sheet_name = 'user medal info'
              AND json_extract(row_data, '$.medalType') IS NOT NULL
              AND json_extract(row_data, '$.medalLevel') IS NOT NULL
            """;

    /** 表名与它的重建脚本，顺序即依赖顺序（彼此独立，顺序只影响日志可读性）。 */
    private static final Map<String, String> SCRIPTS = scripts();

    private static Map<String, String> scripts() {
        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("body_measurement", BODY_MEASUREMENT_SQL);
        scripts.put("sleep_session", SLEEP_SESSION_SQL);
        scripts.put("daily_activity", DAILY_ACTIVITY_SQL);
        scripts.put("daily_vitals", DAILY_VITALS_SQL);
        scripts.put("sleep_report", SLEEP_REPORT_SQL);
        scripts.put("ecg_record", ECG_RECORD_SQL);
        scripts.put("daily_sport_stat", DAILY_SPORT_STAT_SQL);
        scripts.put("sport_daily_dimen_stat", SPORT_DIMEN_STAT_SQL);
        scripts.put("daily_health_stat", DAILY_HEALTH_STAT_SQL);
        scripts.put("daily_health_samplestat", DAILY_HEALTH_SAMPLESTAT_SQL);
        scripts.put("run_level", RUN_LEVEL_SQL);
        scripts.put("medal", MEDAL_SQL);
        // 不能用 Map.copyOf：它不保证迭代顺序，而这里需要可预期的重建顺序（日志与排障都依赖它）
        return java.util.Collections.unmodifiableMap(scripts);
    }

    /** 重建涉及的表，清空顺序与 {@link #SCRIPTS} 一致。 */
    static final List<String> TABLES = List.of(
            "body_measurement", "sleep_session", "daily_activity", "daily_vitals",
            "sleep_report", "ecg_record", "daily_sport_stat", "sport_daily_dimen_stat",
            "daily_health_stat", "daily_health_samplestat", "run_level", "medal");

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final SqliteSchema schema;

    public DerivedTables(JdbcClient jdbc, TransactionOperations transactions, SqliteSchema schema) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.schema = schema;
    }

    /**
     * 清空并重建四张派生表，返回各自行数。
     *
     * <p>幂等：同一份源数据重跑多少次结果都一样，所以可以放心挂在每次同步的末尾。
     */
    public Counts rebuild() {
        schema.ensure();
        long startedAt = System.nanoTime();
        transactions.executeWithoutResult(status -> {
            for (String table : TABLES) {
                jdbc.sql("DELETE FROM " + table).update();
            }
            for (String sql : SCRIPTS.values()) {
                jdbc.sql(sql).update();
            }
        });
        Counts counts = counts();
        log.info("派生表已重建（{} ms）：体重 {} 次、睡眠 {} 段、每日活动 {} 天、每日体征 {} 天、"
                        + "睡眠报告 {} 条、ECG {} 条、每日运动 {} 天、分类型 {} 行、"
                        + "健康统计 {} 行、样本统计 {} 行、跑力 {} 天、勋章 {} 行",
                (System.nanoTime() - startedAt) / 1_000_000,
                counts.bodyMeasurements(), counts.sleepSessions(), counts.activityDays(), counts.vitalDays(),
                counts.sleepReports(), counts.ecgRecords(), counts.dailySportStats(),
                counts.sportDimenStats(), counts.dailyHealthStats(), counts.dailyHealthSamplestats(),
                counts.runLevels(), counts.medals());
        return counts;
    }

    /** 只读行数，不重建；表不存在时按 0 处理。 */
    public Counts counts() {
        try {
            return new Counts(count("body_measurement"), count("sleep_session"),
                    count("daily_activity"), count("daily_vitals"),
                    count("sleep_report"), count("ecg_record"),
                    count("daily_sport_stat"), count("sport_daily_dimen_stat"),
                    count("daily_health_stat"), count("daily_health_samplestat"),
                    count("run_level"), count("medal"));
        } catch (RuntimeException e) {
            log.debug("读取派生表行数失败", e);
            return Counts.empty();
        }
    }

    /**
     * 四张表是否都还是空的。
     *
     * <p>用来决定要不要跳过重建：刚升级到 v7 的库、被人手工清过表的库都需要无条件回填，
     * 而「本次同步没有任何文件重导」时源表没变，重建只是白算一遍。
     */
    public boolean isEmpty() {
        Counts counts = counts();
        return counts.bodyMeasurements() == 0 && counts.sleepSessions() == 0
                && counts.activityDays() == 0 && counts.vitalDays() == 0
                && counts.sleepReports() == 0 && counts.ecgRecords() == 0
                && counts.dailySportStats() == 0 && counts.sportDimenStats() == 0
                && counts.dailyHealthStats() == 0 && counts.dailyHealthSamplestats() == 0
                && counts.runLevels() == 0 && counts.medals() == 0;
    }

    private int count(String table) {
        Integer value = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).optional().orElse(0);
        return value == null ? 0 : value;
    }
}
