package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 天气对配速的影响：把有天气记录的跑步按温度、湿度、风速、降水分组，
 * 比较各组的加权平均配速与整体基线的差异。
 *
 * <p>配速一律用「组内总时长 / 组内总距离」计算，而不是对单次配速取平均，
 * 避免短距离快跑把长距离慢跑的影响盖过去。
 *
 * @param from                        查询区间开始日期
 * @param to                          查询区间结束日期
 * @param totalRuns                   区间内跑步总数
 * @param sampleSize                  同时具备配速与至少一项天气指标的样本数
 * @param baselinePaceSecondsPerKm    样本整体加权平均配速
 * @param baselinePaceText            易读配速
 * @param temperatureBuckets          按起跑气温分组
 * @param humidityBuckets             按起跑湿度分组
 * @param windBuckets                 按起跑风速分组
 * @param dry                         无降水样本
 * @param wet                         有降水样本
 * @param temperaturePaceCorrelation  气温与配速的 Pearson 相关系数，正数表示越热越慢；样本不足为 null
 * @param text                        一句话结论
 */
public record WeatherImpact(
        LocalDate from,
        LocalDate to,
        int totalRuns,
        int sampleSize,
        Double baselinePaceSecondsPerKm,
        String baselinePaceText,
        List<Bucket> temperatureBuckets,
        List<Bucket> humidityBuckets,
        List<Bucket> windBuckets,
        Bucket dry,
        Bucket wet,
        Double temperaturePaceCorrelation,
        String text) {

    /**
     * 一个天气区间内的样本汇总。
     *
     * @param label                    区间标签，例如 {@code 20-25°C}、{@code 有降水}
     * @param runCount                 样本数
     * @param distanceKm               样本总距离（公里）
     * @param avgPaceSecondsPerKm      加权平均配速
     * @param avgPaceText              易读配速
     * @param paceDeltaSecondsPerKm    相对整体基线慢多少秒/公里，正数表示更慢
     * @param avgTemperatureC          样本平均气温（°C）
     * @param avgHumidityPercent       样本平均湿度（%）
     * @param avgWindSpeedKmh          样本平均风速（km/h）
     */
    public record Bucket(
            String label,
            int runCount,
            double distanceKm,
            Double avgPaceSecondsPerKm,
            String avgPaceText,
            Double paceDeltaSecondsPerKm,
            Double avgTemperatureC,
            Double avgHumidityPercent,
            Double avgWindSpeedKmh) {
    }
}
