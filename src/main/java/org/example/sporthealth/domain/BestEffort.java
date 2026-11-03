package org.example.sporthealth.domain;

/**
 * 一次跑步中某个目标距离的最佳成绩。
 *
 * @param targetMeters     目标距离（米），例如 1000、5000
 * @param durationSeconds  最佳用时（秒）
 * @param durationText     易读用时
 * @param paceSecondsPerKm 折算配速（秒/公里）
 * @param paceText         易读配速
 * @param startKm          该段起点在整条轨迹上的距离（公里）
 * @param endKm            该段终点在整条轨迹上的距离（公里）
 */
public record BestEffort(
        double targetMeters,
        double durationSeconds,
        String durationText,
        Double paceSecondsPerKm,
        String paceText,
        double startKm,
        double endKm) {
}
