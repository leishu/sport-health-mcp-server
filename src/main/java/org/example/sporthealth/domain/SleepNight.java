package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

import org.example.sporthealth.support.SleepFormat;

/**
 * 一晚夜间睡眠（{@code sleep_date} = 醒来那天，与华为 App 的「昨晚睡了多久」一致）。
 *
 * <p><b>两套来源，字段覆盖不同</b>：
 * <ul>
 *   <li>{@code sources} 含 {@code stages}：有睡眠分期（深睡/浅睡/快速眼动/清醒），覆盖全部历史；</li>
 *   <li>{@code sources} 含 {@code report}：有官方睡眠报告，才有评分、效率、HRV、血氧与入睡潜伏期；
 *       报告只从 2026-06-13 起存在。</li>
 * </ul>
 * 只有分期、没有报告的那些夜晚，{@code score} / {@code efficiencyPercent} /
 * {@code hrvMs} / {@code spo2Percent} / {@code sleepLatencyMinutes} 一律是 {@code null}
 * —— <b>缺数据，不是 0</b>。调用方应先看 {@link SleepCoverage} 的计数再决定怎么解读。
 *
 * <p>午睡不在这里：华为把午睡单独标成 {@code PROFESSIONAL_SLEEP_NOON}，而且经常和夜睡粘成一段，
 * 混进夜睡会让「睡了多久」凭空变大，所以本工具只报夜睡。
 *
 * @param date                  醒来日期
 * @param bedtime               入睡时刻（会话开始，含入睡前的清醒期），{@code yyyy-MM-dd HH:mm:ss}
 * @param waketime              起床时刻（会话结束），{@code yyyy-MM-dd HH:mm:ss}
 * @param timeInBedMinutes      卧床时长 = waketime - bedtime
 * @param asleepMinutes         实际睡着时长 = 深睡 + 浅睡 + 快速眼动
 * @param awakeMinutes          夜间清醒总时长（WASO 口径：睡着之后、起床之前的清醒，不含入睡前那段
 *                              潜伏期，也不含起床前那段），所以
 *                              {@code timeInBed >= asleepMinutes + awakeMinutes}
 * @param wakeCount             醒来次数：连续清醒段聚合后、去掉首尾两段、时长 ≥2 分钟的次数
 * @param deepMinutes           深睡分钟，缺分期数据时为 null
 * @param lightMinutes          浅睡分钟，缺分期数据时为 null
 * @param remMinutes            快速眼动分钟，缺分期数据时为 null
 * @param sleepLatencyMinutes   入睡潜伏期（分钟），只有报告有
 * @param efficiencyPercent     睡眠效率（%），只有报告有，取值 0~100
 * @param score                 华为睡眠评分，只有报告有，取值 0~100
 * @param hrvMs                 平均 HRV（RMSSD，毫秒），只有报告有
 * @param spo2Percent           平均血氧饱和度（%），只有报告且设备上报了才有值
 * @param sources               本行的数据来源，{@code stages} / {@code report} 的组合
 */
public record SleepNight(
        LocalDate date,
        String bedtime,
        String waketime,
        int timeInBedMinutes,
        int asleepMinutes,
        int awakeMinutes,
        Integer wakeCount,
        Integer deepMinutes,
        Integer lightMinutes,
        Integer remMinutes,
        Integer sleepLatencyMinutes,
        Double efficiencyPercent,
        Double score,
        Double hrvMs,
        Double spo2Percent,
        List<String> sources) {

    /** 是否有睡眠报告（决定评分类字段是否有值）。 */
    public boolean hasReport() {
        return sources != null && sources.contains("report");
    }

    /** 是否有分期数据（决定深睡/浅睡/快速眼动是否有值）。 */
    public boolean hasStages() {
        return sources != null && sources.contains("stages");
    }

    /** 睡着时长的可读写法，例如 {@code 6h 14m}。 */
    public String asleepText() {
        return SleepFormat.hoursAndMinutes(asleepMinutes);
    }
}
