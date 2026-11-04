package org.example.sporthealth.domain;

import java.time.Instant;

/**
 * 某一时刻在某一地点的天气快照。起跑与结束各一份，字段直接对应 open-meteo 的 hourly 变量。
 *
 * <p>{@code observedAtUtc} 是<b>对齐后的整点</b>，不是跑步时刻本身：open-meteo 只提供逐小时粒度，
 * 中间值由 {@code WeatherInterpolator} 插值得到，对齐点记录下来便于回溯与排查。
 *
 * <p>所有天气字段都可为 null：接口正常返回但某一小时缺值（早期数据、模型未覆盖）时保持 null，
 * 绝不能当成 0 摄氏度。
 *
 * @param observedAtUtc        用于计算的整点（UTC）
 * @param temperatureC         2 米气温（°C）
 * @param apparentTemperatureC 体感温度（°C），综合风寒与湿度，非实测
 * @param humidityPercent      相对湿度（%）
 * @param precipitationMm      前一小时累计降水量（mm）
 * @param windSpeedKmh         10 米风速（km/h）
 * @param windDirectionDegrees 10 米风向（°，气象学定义：风的来向）
 * @param weatherCode          WMO 天气代码，枚举值不做插值
 */
public record WeatherSnapshot(
        Instant observedAtUtc,
        Double temperatureC,
        Double apparentTemperatureC,
        Double humidityPercent,
        Double precipitationMm,
        Double windSpeedKmh,
        Double windDirectionDegrees,
        Integer weatherCode) {

    /** 一个字段都没有时视为该时刻没有可用天气，用于判定 {@code UNAVAILABLE}。 */
    public boolean isEmpty() {
        return temperatureC == null
                && apparentTemperatureC == null
                && humidityPercent == null
                && precipitationMm == null
                && windSpeedKmh == null
                && windDirectionDegrees == null
                && weatherCode == null;
    }
}
