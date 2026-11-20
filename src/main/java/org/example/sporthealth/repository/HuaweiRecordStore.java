package org.example.sporthealth.repository;

import java.time.LocalDate;
import java.util.List;

import org.example.sporthealth.domain.EcgRecord;
import org.example.sporthealth.domain.Medal;
import org.example.sporthealth.domain.SampleSequence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Component;

/**
 * 华为「元数据/记录」导出的只读访问：勋章、ECG 记录、样本序列。
 *
 * <p>这三张表是 v8 新增的「华为全量导出原样保留」：勋章和 ECG 元数据由派生表重建，
 * 样本序列是源表。列表限定要小——勋章 137 行、ECG 5 行，样本序列的元数据也只有
 * recordId/类型/时间/设备——{@code limit} 给了硬上限，调用方通过 {@code offset} 翻页。
 *
 * <p>时间字段全部是华为导出的原文文本（如 {@code 2026-01-11T08:06:59.000Z}），
 * 区间过滤靠 {@code substr(start_time,1,10)} 做宽松比较，列表输出直接透传。
 */
@Component
public class HuaweiRecordStore {

    private final JdbcClient jdbc;

    public HuaweiRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 勋章，{@code take_date} 降序，同类型同等级按获得时间倒序。 */
    public List<Medal> medals(String medalType, String medalLevel, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT medal_type, medal_level, gain_count, take_date, modify_time"
                        + " FROM medal WHERE 1=1");
        if (isPresent(medalType)) {
            sql.append(" AND medal_type = :medal_type");
        }
        if (isPresent(medalLevel)) {
            sql.append(" AND medal_level = :medal_level");
        }
        sql.append(" ORDER BY take_date DESC, medal_type, medal_level LIMIT :limit");
        StatementSpec spec = jdbc.sql(sql.toString());
        if (isPresent(medalType)) {
            spec.param("medal_type", medalType.trim());
        }
        if (isPresent(medalLevel)) {
            spec.param("medal_level", medalLevel.trim());
        }
        return spec.param("limit", limit)
                .query((rs, rowNum) -> new Medal(
                        rs.getString("medal_type"),
                        rs.getString("medal_level"),
                        RunRows.integer(rs, "gain_count"),
                        rs.getString("take_date"),
                        rs.getString("modify_time")))
                .list();
    }

    /** ECG 记录元数据，{@code start_time} 降序，最新在前。 */
    public List<EcgRecord> ecgRecords(int limit) {
        return jdbc.sql("SELECT record_id, start_time, end_time, average_heart_rate,"
                        + " ecg_arrhy_type, ecg_data_length, data_sources, ecg_app_version,"
                        + " package_name, user_symptom, device_code,"
                        + " detail_sample_count, extend_sample_count"
                        + " FROM ecg_record ORDER BY start_time DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, rowNum) -> new EcgRecord(
                        rs.getString("record_id"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        RunRows.integer(rs, "average_heart_rate"),
                        RunRows.integer(rs, "ecg_arrhy_type"),
                        RunRows.integer(rs, "ecg_data_length"),
                        rs.getString("data_sources"),
                        rs.getString("ecg_app_version"),
                        rs.getString("package_name"),
                        RunRows.integer(rs, "user_symptom"),
                        rs.getString("device_code"),
                        RunRows.integer(rs, "detail_sample_count"),
                        RunRows.integer(rs, "extend_sample_count")))
                .list();
    }

    /**
     * 样本序列元数据，{@code start_time} 降序，最新在前。
     *
     * @param from 日期区间起点（含），{@code null} 时不限制
     * @param to   日期区间终点（含），{@code null} 时不限制
     */
    public List<SampleSequence> sampleSequences(String typeCode, LocalDate from, LocalDate to, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT record_id, type_code, data_id, sub_type, start_time, end_time,"
                        + " time_zone, device_code FROM sample_sequence WHERE 1=1");
        if (isPresent(typeCode)) {
            sql.append(" AND type_code = :type_code");
        }
        if (from != null && to != null) {
            sql.append(" AND substr(start_time,1,10) >= :from AND substr(start_time,1,10) <= :to");
        }
        sql.append(" ORDER BY start_time DESC LIMIT :limit");
        StatementSpec spec = jdbc.sql(sql.toString());
        if (isPresent(typeCode)) {
            spec.param("type_code", Integer.parseInt(typeCode.trim()));
        }
        if (from != null && to != null) {
            spec.param("from", from.toString()).param("to", to.toString());
        }
        return spec.param("limit", limit)
                .query((rs, rowNum) -> new SampleSequence(
                        rs.getString("record_id"),
                        rs.getInt("type_code"),
                        RunRows.integer(rs, "data_id"),
                        RunRows.integer(rs, "sub_type"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getString("time_zone"),
                        rs.getString("device_code")))
                .list();
    }

    /** 样本序列的总行数（含 typeCode 与日期区间过滤），用于「还有多少数据没列」。 */
    public long sampleSequenceCount(String typeCode, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sample_sequence WHERE 1=1");
        if (isPresent(typeCode)) {
            sql.append(" AND type_code = :type_code");
        }
        if (from != null && to != null) {
            sql.append(" AND substr(start_time,1,10) >= :from AND substr(start_time,1,10) <= :to");
        }
        StatementSpec spec = jdbc.sql(sql.toString());
        if (isPresent(typeCode)) {
            spec.param("type_code", Integer.parseInt(typeCode.trim()));
        }
        if (from != null && to != null) {
            spec.param("from", from.toString()).param("to", to.toString());
        }
        return spec.query(Long.class).optional().orElse(0L);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
