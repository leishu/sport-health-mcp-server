package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 跑力趋势：只保留「日期 + 跑力值」两个字段。
 *
 * <p>368 天全量返回约几十 KB，对模型没有意义；调用方用 {@code days} 控制窗口，
 * 需要看某一天的档位配速时再按 {@code date} 查 {@code get_run_level}。
 *
 * @param from        窗口开始日期
 * @param to          窗口结束日期
 * @param days        实际覆盖的天数（有数据的天数，不一定等于自然日数）
 * @param points      按日期升序的跑力点
 * @param scoreChange 窗口内跑力变化（末值 - 首值），不足两个点时为空
 * @param text        一句话结论
 */
public record RunLevelHistory(
        LocalDate from,
        LocalDate to,
        int days,
        List<Point> points,
        Double scoreChange,
        String text) {

    /**
     * 一个跑力点。
     *
     * @param date  日期
     * @param score 当天跑力
     */
    public record Point(LocalDate date, Double score) {
    }
}
