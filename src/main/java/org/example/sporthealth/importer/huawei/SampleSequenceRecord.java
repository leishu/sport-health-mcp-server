package org.example.sporthealth.importer.huawei;

import java.time.Instant;

/**
 * 「Sample sequence data」里的一条原始记录。
 *
 * <p>这类记录与健康明细的区别是：{@code detailData}/{@code extendData} 通常是一大段
 * 自定义二进制或 JSON 数组（ECG 波形、HRV RRI、睡眠分期位图），没有统一的采样点结构。
 * 因此这里不尝试解读数值，只把元数据与三段原始文本完整交给写入层。
 *
 * @param recordId    华为 recordId，全局唯一，作为主键
 * @param typeCode    记录类型：700004 心律失常、700008 睡眠碎片、700009 ECG、
 *                    700013 睡眠报告、700014 放松训练、700021 HRV RRI
 * @param dataId      华为内部 dataId
 * @param subType     子类型，当前均为 0
 * @param startTime   开始时刻（UTC）
 * @param endTime     结束时刻（UTC）
 * @param timeZone    原始时区文本，如 {@code +0800}
 * @param deviceCode  设备码
 * @param summaryData 汇总 JSON 原文，可能为空串
 * @param detailData  明细原文，可能是 JSON 数组、十六进制串或自定义文本
 * @param extendData  扩展原文，ECG 记录里是另一路波形
 */
public record SampleSequenceRecord(
        String recordId,
        int typeCode,
        Long dataId,
        Integer subType,
        Instant startTime,
        Instant endTime,
        String timeZone,
        String deviceCode,
        String summaryData,
        String detailData,
        String extendData) {
}
