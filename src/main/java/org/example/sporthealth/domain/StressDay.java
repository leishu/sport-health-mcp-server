package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 单日的压力概况。
 *
 * <p>压力分档按华为的 0~100 分口径：{@code 0–29 放松 / 30–59 正常 / 60–79 中等 / 80–100 高压}。
 * 实测全库取值范围只有 15~53，所以只会出现前两档 —— 到不了 60 不代表没压力，
 * 是这台设备的算法很少给出高分（详见 {@code get_stress_series} 的 notes）。
 *
 * <p>{@code levels} 是「每个档位占多少个样本」，不是时长：压力是离散测量，
 * 中间有大量没测的空隙，换算成分钟会凭空造出时长。
 *
 * @param date            日期
 * @param samples         样本数
 * @param min             当天最低压力分
 * @param average         当天平均
 * @param max             当天最高
 * @param level           全天均值所在的档位
 * @param firstSample     首次测量时刻 {@code HH:mm}
 * @param lastSample      末次测量时刻 {@code HH:mm}
 * @param medianGapMinutes 采样间隔中位数（分钟）
 * @param buckets         分桶曲线
 * @param levels          各档位的样本数
 * @param peak            压力最高的一次测量
 * @param calmest         压力最低的一次测量
 */
public record StressDay(
        LocalDate date,
        int samples,
        int min,
        Double average,
        int max,
        String level,
        String firstSample,
        String lastSample,
        Integer medianGapMinutes,
        List<StressBucket> buckets,
        Map<String, Integer> levels,
        VitalsSample peak,
        VitalsSample calmest) {
}
