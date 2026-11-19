package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 华为官方成绩与 Riegel 外推的并列对照。
 *
 * <p>两种预测的口径完全不同：官方值是华为基于个人跑力给出的目标成绩，
 * Riegel 是本项目用区间内最佳努力按 {@code T2 = T1 × (D2/D1)^1.06} 外推的。
 * 因此这里不做「谁替代谁」的判断，只把两组数字放在同一张表里并逐行标注来源，
 * 由调用方决定采信哪一组。
 *
 * @param date                   官方快照日期
 * @param score                  官方跑力
 * @param source                 官方来源标识，固定 {@code HUAWEI_OFFICIAL}
 * @param withinRange            快照日期是否落在所查区间内
 * @param staleDays              快照日期比区间结束早多少天（区间内为 0）
 * @param rows                   同距离的官方值 / Riegel 值与差值
 * @param unavailableTargetsKm   Riegel 有预测但官方没有对应档位的目标距离
 * @param text                   一句话结论
 */
public record OfficialRaceComparison(
        LocalDate date,
        Double score,
        String source,
        boolean withinRange,
        long staleDays,
        List<Row> rows,
        List<Double> unavailableTargetsKm,
        String text) {

    /**
     * 一个目标距离的两种预测。
     *
     * @param targetKm                  目标距离（公里）
     * @param targetMeters              目标距离（米）
     * @param officialSeconds           官方成绩（秒）
     * @param officialText              官方成绩易读文本
     * @param officialPaceSecondsPerKm  官方配速（秒/公里）
     * @param officialPaceText          官方配速易读文本
     * @param officialSource            官方来源标识，固定 {@code HUAWEI_OFFICIAL}
     * @param riegelSeconds             Riegel 预测（秒）
     * @param riegelText                Riegel 预测易读文本
     * @param riegelSource              Riegel 那一条的来源：{@code ACTUAL} 或 {@code RIEGEL}
     * @param deltaSeconds              官方 - Riegel（秒）；正数表示官方更慢
     * @param deltaText                 差值易读文本；基线按 {@code riegelSource} 区分「区间实测」与「Riegel 外推」
     */
    public record Row(
            double targetKm,
            double targetMeters,
            double officialSeconds,
            String officialText,
            Double officialPaceSecondsPerKm,
            String officialPaceText,
            String officialSource,
            Double riegelSeconds,
            String riegelText,
            String riegelSource,
            Double deltaSeconds,
            String deltaText) {
    }
}
