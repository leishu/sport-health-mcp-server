package org.example.sporthealth.domain;

/**
 * 「Sample sequence data」里的一条原始记录的元数据。
 *
 * <p>{@code summaryData} / {@code detailData} / {@code extendData} 三段原文都在
 * {@code sample_sequence} 里，但对 MCP 来说内容太大（ECG 的 {@code detailData} 几十万字符），
 * 所以本结构只提元数据，有需要的调用方再按 {@code recordId} 去读原文。
 */
public record SampleSequence(
        String recordId,
        int typeCode,
        Integer dataId,
        Integer subType,
        String startTime,
        String endTime,
        String timeZone,
        String deviceCode) {
}
