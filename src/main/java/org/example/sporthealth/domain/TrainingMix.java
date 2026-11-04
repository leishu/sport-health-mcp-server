package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 区间内的训练类型构成。
 *
 * @param from      区间开始日期
 * @param to        区间结束日期
 * @param totalRuns 记录条数
 * @param types     按类型汇总，按距离从多到少排列
 * @param runs      逐条分类结果（轻量字段）
 * @param text      一句话结论
 */
public record TrainingMix(
        LocalDate from,
        LocalDate to,
        int totalRuns,
        List<TypeSummary> types,
        List<RunTypeRow> runs,
        String text) {

    /**
     * 一个训练类型的汇总。
     *
     * @param type       类型标识
     * @param typeText   中文类型名
     * @param runCount   记录条数
     * @param distanceKm 该类型总距离（公里）
     * @param percent    距离占比（%）
     */
    public record TypeSummary(
            String type,
            String typeText,
            int runCount,
            double distanceKm,
            double percent) {
    }

    /**
     * 逐条分类的轻量结果。
     *
     * @param runId      记录 id
     * @param date       本地日期
     * @param distanceKm 距离（公里）
     * @param paceText   易读配速
     * @param type       类型标识
     * @param typeText   中文类型名
     * @param confidence 置信度
     */
    public record RunTypeRow(
            String runId,
            LocalDate date,
            double distanceKm,
            String paceText,
            String type,
            String typeText,
            String confidence) {
    }
}
