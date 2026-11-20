package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 一天自动心率的完整结果。
 *
 * @param resolution 生效的聚合粒度（{@code minute} / {@code 5min} / {@code 15min} / {@code hour}）
 * @param bucketMinutes 一个桶包含的分钟数（1 / 5 / 15 / 60）
 * @param notes      读这张表时容易踩的口径提示
 */
public record HeartRateSeries(
        LocalDate date,
        String resolution,
        int bucketMinutes,
        HeartRateDayStats stats,
        HeartRateExtreme peak,
        HeartRateExtreme lowest,
        List<HeartRateBucket> buckets,
        List<HeartRateActivity> activities,
        List<String> notes) {
}
