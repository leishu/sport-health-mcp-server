package org.example.sporthealth.domain;

/**
 * 一次华为 ECG 记录的元数据（波形原文仍在 {@code sample_sequence}，不重复）。
 *
 * <p>波形原文可能比一个表还大（每条 15,000 个采样），所以这张表只存元数据与采样计数，
 * 想看波形从 {@code sample_sequence} 里按 {@code recordId} 读（见 {@code get_sample_sequences}）。
 */
public record EcgRecord(
        String recordId,
        String startTime,
        String endTime,
        Integer averageHeartRate,
        Integer ecgArrhyType,
        Integer ecgDataLength,
        String dataSources,
        String ecgAppVersion,
        String packageName,
        Integer userSymptom,
        String deviceCode,
        Integer detailSampleCount,
        Integer extendSampleCount) {
}
