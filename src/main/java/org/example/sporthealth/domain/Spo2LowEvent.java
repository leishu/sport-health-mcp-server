package org.example.sporthealth.domain;

/**
 * 设备自己下发的「血氧偏低」提醒。
 *
 * <p>来源是 {@code health_metric} 里的 {@code LOW_BLOOD_OXYGEN}
 * （{@code BLOOD_OXYGEN_REMIND} 是同一时刻的重复记录，只取前者）。
 * 阈值在 {@code value_text.lowSpO2Threshold} 里，实测为 90%。
 *
 * <p>这类事件和「血氧样本 < 95」是两回事：一个是设备判定的越界提醒，
 * 一个是原始读数。低氧判断只认这里。
 *
 * @param time      发生时刻 {@code HH:mm}
 * @param value     触发时的血氧值（%）
 * @param threshold 当时的阈值，取不到时为 {@code null}
 */
public record Spo2LowEvent(String time, int value, Double threshold) {
}
