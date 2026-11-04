package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 基于区间内最佳努力（1K / 5K / 10K 等）的比赛成绩预测。
 *
 * <p>当前使用 Riegel 公式：{@code T2 = T1 × (D2 / D1)^1.06}。参考成绩越接近目标距离，
 * 预测越可靠；因此每条预测都带上实际使用的参考距离与 {@code confidence}，
 * 不使用“全马一定准”这类过度承诺。
 *
 * @param from        统计区间开始日期
 * @param to          统计区间结束日期
 * @param method      预测方法标识，当前固定 {@code riegel}
 * @param exponent    Riegel 指数
 * @param references  区间内实际跑出的最佳努力，按距离升序
 * @param predictions 目标距离的预测结果，按距离升序
 * @param text        一句话结论
 */
public record RacePrediction(
        LocalDate from,
        LocalDate to,
        String method,
        double exponent,
        List<PersonalBestEffort> references,
        List<Prediction> predictions,
        String text) {

    /**
     * 一个目标距离的结果。
     *
     * @param targetMeters          目标距离（米）
     * @param targetKm              目标距离（公里）
     * @param source                {@code ACTUAL} 表示区间内跑出过这个距离，{@code RIEGEL} 表示外推
     * @param predictedSeconds      预测或实测用时（秒）
     * @param predictedText         易读用时
     * @param paceSecondsPerKm      折算配速（秒/公里）
     * @param paceText              易读配速
     * @param confidence            置信度：HIGH / MEDIUM / LOW
     * @param referenceMeters       使用的参考距离（米）
     * @param referenceRunId        参考成绩对应的记录 id
     * @param referenceDate         参考成绩日期
     * @param referenceDurationSeconds 参考成绩用时（秒）
     */
    public record Prediction(
            double targetMeters,
            double targetKm,
            String source,
            double predictedSeconds,
            String predictedText,
            Double paceSecondsPerKm,
            String paceText,
            String confidence,
            double referenceMeters,
            String referenceRunId,
            LocalDate referenceDate,
            double referenceDurationSeconds) {
    }
}
