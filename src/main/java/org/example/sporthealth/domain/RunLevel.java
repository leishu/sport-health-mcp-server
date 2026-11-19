package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 华为官方跑力（{@code run_level}）的一天快照，来自导出文件 {@code user run level data.xls}。
 *
 * <p>与 {@link RacePrediction} 的区别：那是本项目用 Riegel 公式从实际跑步成绩外推，
 * 这里是华为自己算的跑力值、五档训练配速与官方预测成绩。两者口径不同，
 * 只做并列展示并标明来源，不互相覆盖。
 *
 * @param date          快照日期（{@code recordDay}）
 * @param score         当前跑力（{@code currentRunLevel}）
 * @param zones         A/E/I/M/R 五档配速，按该顺序；缺数据的档位不出现
 * @param officialTimes 官方成绩，按 1/3/5/10 公里、半马、全马顺序；缺数据的距离不出现
 */
public record RunLevel(
        LocalDate date,
        Double score,
        List<Zone> zones,
        List<OfficialTime> officialTimes) {

    /**
     * 一档训练配速。
     *
     * @param code             华为原文的档位字母：A / E / I / M / R
     * @param name             该档位的通用叫法（有氧耐力 / 轻松跑 / 间歇跑 / 马拉松配速 / 重复跑）
     * @param speedKmh         华为原文速度（km/h）
     * @param paceSecondsPerKm 换算后的配速（秒/公里）
     * @param paceText         易读配速
     */
    public record Zone(
            String code,
            String name,
            Double speedKmh,
            Double paceSecondsPerKm,
            String paceText) {
    }

    /**
     * 官方给某个距离的成绩。
     *
     * @param distanceKm           距离（公里），半马为 21.0975
     * @param distanceMeters       距离（米）
     * @param seconds              成绩（秒）
     * @param timeText             易读成绩，如 {@code 22:47} / {@code 3:35:26}
     * @param paceSecondsPerKm     折算配速（秒/公里）
     * @param paceText             易读配速
     */
    public record OfficialTime(
            double distanceKm,
            double distanceMeters,
            double seconds,
            String timeText,
            Double paceSecondsPerKm,
            String paceText) {
    }
}
