package org.example.sporthealth.domain;

import java.util.List;

/**
 * 训练趋势：按周或按月切分的连续周期对比。
 *
 * @param period  分组粒度：week 或 month
 * @param count   返回的周期数
 * @param periods 周期列表，按时间升序（旧的在前面）
 * @param text    一句话结论
 */
public record TrainingTrend(
        String period,
        int count,
        List<TrendPeriod> periods,
        String text) {
}
