package org.example.sporthealth.importer.huawei;

import java.time.LocalDateTime;

/**
 * 华为健康明细里的一个采样点，对应 {@code health_metric} 一行。
 *
 * @param recordId  华为 {@code recordId}，同一份数据在不同子目录副本里保持一致，用于去重
 * @param metricKey {@code samplePoints[].key}，如 {@code DYNAMIC_HEART_RATE}
 * @param typeCode  记录级 {@code type}，如 7=动态心率、9=睡眠分期
 * @param startTime 采样开始（本地时间）
 * @param endTime   采样结束（本地时间）
 * @param valueNum  从 {@code value} 里抽出的主数值；无法确定主数值时为 null
 * @param valueText 原始 {@code value} 文本，超过 2000 字符截断，JSON 明细仍可完整保留在大字段里
 * @param unit      导出里的 {@code unit}，多数为 "0"（无单位，实际单位见指标字典）
 * @param deviceCode 采集设备编码
 * @param timeZone  导出时区，如 "+0800"
 */
public record MetricSample(
        String recordId,
        String metricKey,
        int typeCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Double valueNum,
        String valueText,
        String unit,
        String deviceCode,
        String timeZone) {
}
