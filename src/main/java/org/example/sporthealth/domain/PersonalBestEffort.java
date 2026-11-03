package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 区间内某个目标距离（1 公里、5 公里等）的最佳成绩，跨记录比较。
 *
 * <p>与 {@link BestEffort} 的区别：后者回答“这一次里哪一段最快”，这里回答
 * “区间内哪一次跑出的这一段最快”，因此额外带 {@code runId} 与 {@code date}。
 *
 * <p>距离与时间都沿 GPS 轨迹累积并插值，找不到可用轨迹时该目标距离不会出现在结果里。
 *
 * @param targetMeters     目标距离（米）
 * @param runId            跑出该成绩的记录 id
 * @param date             该记录的本地日期
 * @param durationSeconds  最佳用时（秒）
 * @param durationText     易读用时
 * @param paceSecondsPerKm 折算配速（秒/公里）
 * @param paceText         易读配速
 * @param startKm          该段起点在整条轨迹上的距离（公里）
 * @param endKm            该段终点在整条轨迹上的距离（公里）
 */
public record PersonalBestEffort(
        double targetMeters,
        String runId,
        LocalDate date,
        double durationSeconds,
        String durationText,
        Double paceSecondsPerKm,
        String paceText,
        double startKm,
        double endKm) {
}
