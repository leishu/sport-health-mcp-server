package org.example.sporthealth.domain;

/**
 * 一天里的最高点或最低点。
 *
 * <p>{@code sustainedMinutes} 是「围绕这一点、连续保持在 bpm ± 5 以内」的总时长（分钟）。
 * 它比单点数值更能说明问题：跑步冲上去的 153 会连着 3~20 分钟，
 * 而翻个身蹭到传感器的 94 只有孤立 1 分钟 —— 后者不该当峰值解读。
 *
 * @param time             出现时刻（{@code HH:mm}）
 * @param bpm              心率
 * @param sustainedMinutes 连续维持在 bpm ± 5 以内的时间跨度，至少 1
 */
public record HeartRateExtreme(String time, int bpm, int sustainedMinutes) {
}
