package org.example.sporthealth.domain;

/**
 * 官方睡眠报告里除「评分 / 效率 / HRV / 血氧」之外的补充指标。
 *
 * <p>只挑语义明确的字段。报告里还有 {@code deepPart}、{@code rdi}、
 * {@code *DayToBaseline}、{@code validData} 等字段，单位与含义都没确认
 * （{@code deepPart} 不是深睡百分比，也不是深睡分钟；{@code rdi} 全库恒为 0；
 * {@code validData} 是浮点位模式），拿出去只会被误读，故不输出。
 *
 * @param avgHeartRateBpm    夜间平均心率（bpm）
 * @param avgBreathRatePerMin 夜间平均呼吸率（次/分）
 * @param snoreIndex         打鼾指数（原始字段 {@code snoreFreq}，华为未公开单位，多数夜晚为 0~3）
 * @param bedTime            上床时刻（报告口径，可能早于入睡时刻）
 * @param fallAsleepTime     睡着时刻（报告口径，通常等于报告窗口的开始）
 * @param risingTime         起床时刻（报告口径）
 */
public record SleepReportExtras(
        Double avgHeartRateBpm,
        Double avgBreathRatePerMin,
        Double snoreIndex,
        String bedTime,
        String fallAsleepTime,
        String risingTime) {
}
