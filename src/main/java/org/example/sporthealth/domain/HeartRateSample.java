package org.example.sporthealth.domain;

/**
 * 一个自动心率采样点。
 *
 * @param time {@code HH:mm}，已按 {@code time_zone=+0800} 的本地时间截到分钟
 * @param bpm  心率（次/分）
 */
public record HeartRateSample(String time, int bpm) {
}
