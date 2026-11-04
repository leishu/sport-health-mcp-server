package org.example.sporthealth.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.domain.WeatherImpact;
import org.example.sporthealth.domain.WeatherSnapshot;

/**
 * 天气对配速影响的纯计算：不访问数据库，也不发 HTTP 请求。
 *
 * <p>入参是已经按区间筛过的跑步记录与全量天气行，输出按温度、湿度、风速、降水分组的
 * 加权平均配速。分组内用「总时长 / 总距离」计算，避免短距离快跑拉偏结果。
 *
 * <p>只使用起跑时刻的天气；起跑快照为空时退回结束时刻。没有有效快照的记录直接跳过，
 * 不会把缺测当成 0°C 参与统计。
 */
public final class WeatherImpactAnalyzer {

    /** 小于这个降水量视为无降水（open-meteo 的降水量是前一小时累计值）。 */
    private static final double DRY_THRESHOLD_MM = 0.05d;

    private WeatherImpactAnalyzer() {
    }

    public static WeatherImpact analyze(DateRange range, List<RunActivity> runs,
                                        List<RunWeather> weatherRows, double minDistanceKm) {
        Map<String, RunWeather> byRunId = new HashMap<>();
        for (RunWeather weather : weatherRows) {
            if (weather != null && weather.runId() != null) {
                byRunId.put(weather.runId(), weather);
            }
        }

        List<Sample> samples = new ArrayList<>();
        for (RunActivity run : runs) {
            double distanceKm = run.distanceMeters() / 1000d;
            Double pace = run.avgPaceSecondsPerKm();
            if (distanceKm <= 0 || distanceKm < minDistanceKm || pace == null || pace <= 0) {
                continue;
            }
            RunWeather weather = byRunId.get(run.id());
            if (weather == null || weather.status() != RunWeather.Status.OK) {
                continue;
            }
            WeatherSnapshot snapshot = weather.start() != null ? weather.start() : weather.end();
            if (snapshot == null || snapshot.isEmpty()) {
                continue;
            }
            samples.add(new Sample(run, distanceKm, run.durationSeconds(),
                    snapshot.temperatureC(), snapshot.humidityPercent(),
                    snapshot.windSpeedKmh(), snapshot.precipitationMm(), pace));
        }

        double totalDistanceKm = samples.stream().mapToDouble(Sample::distanceKm).sum();
        double totalDurationSeconds = samples.stream().mapToDouble(Sample::durationSeconds).sum();
        Double baselinePace = totalDistanceKm > 0
                ? PaceFormat.round(totalDurationSeconds / totalDistanceKm, 1)
                : null;

        List<WeatherImpact.Bucket> temperatureBuckets = new ArrayList<>();
        add(temperatureBuckets, bucket("低于 5°C", samples,
                sample -> sample.temperatureC() != null && sample.temperatureC() < 5d, baselinePace));
        add(temperatureBuckets, bucket("5-10°C", samples,
                sample -> inRange(sample.temperatureC(), 5d, 10d), baselinePace));
        add(temperatureBuckets, bucket("10-15°C", samples,
                sample -> inRange(sample.temperatureC(), 10d, 15d), baselinePace));
        add(temperatureBuckets, bucket("15-20°C", samples,
                sample -> inRange(sample.temperatureC(), 15d, 20d), baselinePace));
        add(temperatureBuckets, bucket("20-25°C", samples,
                sample -> inRange(sample.temperatureC(), 20d, 25d), baselinePace));
        add(temperatureBuckets, bucket("25°C 及以上", samples,
                sample -> sample.temperatureC() != null && sample.temperatureC() >= 25d, baselinePace));

        List<WeatherImpact.Bucket> humidityBuckets = new ArrayList<>();
        add(humidityBuckets, bucket("低于 50%", samples,
                sample -> sample.humidityPercent() != null && sample.humidityPercent() < 50d, baselinePace));
        add(humidityBuckets, bucket("50-65%", samples,
                sample -> inRange(sample.humidityPercent(), 50d, 65d), baselinePace));
        add(humidityBuckets, bucket("65-80%", samples,
                sample -> inRange(sample.humidityPercent(), 65d, 80d), baselinePace));
        add(humidityBuckets, bucket("80% 及以上", samples,
                sample -> sample.humidityPercent() != null && sample.humidityPercent() >= 80d, baselinePace));

        List<WeatherImpact.Bucket> windBuckets = new ArrayList<>();
        add(windBuckets, bucket("低于 10 km/h", samples,
                sample -> sample.windSpeedKmh() != null && sample.windSpeedKmh() < 10d, baselinePace));
        add(windBuckets, bucket("10-20 km/h", samples,
                sample -> inRange(sample.windSpeedKmh(), 10d, 20d), baselinePace));
        add(windBuckets, bucket("20 km/h 及以上", samples,
                sample -> sample.windSpeedKmh() != null && sample.windSpeedKmh() >= 20d, baselinePace));

        WeatherImpact.Bucket dry = bucket("无降水", samples,
                sample -> sample.precipitationMm() != null
                        && sample.precipitationMm() <= DRY_THRESHOLD_MM, baselinePace);
        WeatherImpact.Bucket wet = bucket("有降水", samples,
                sample -> sample.precipitationMm() != null
                        && sample.precipitationMm() > DRY_THRESHOLD_MM, baselinePace);

        Double correlation = temperaturePaceCorrelation(samples);
        String text = summaryText(runs.size(), samples.size(), baselinePace, temperatureBuckets, correlation);

        return new WeatherImpact(range.from(), range.to(), runs.size(), samples.size(),
                baselinePace, PaceFormat.pace(baselinePace),
                temperatureBuckets, humidityBuckets, windBuckets, dry, wet, correlation, text);
    }

    private static boolean inRange(Double value, double minInclusive, double maxExclusive) {
        return value != null && value >= minInclusive && value < maxExclusive;
    }

    private static void add(List<WeatherImpact.Bucket> buckets, WeatherImpact.Bucket bucket) {
        if (bucket != null) {
            buckets.add(bucket);
        }
    }

    private static WeatherImpact.Bucket bucket(String label, List<Sample> samples,
                                                Predicate<Sample> filter, Double baselinePace) {
        List<Sample> selected = samples.stream().filter(filter).toList();
        if (selected.isEmpty()) {
            return null;
        }
        double distanceKm = selected.stream().mapToDouble(Sample::distanceKm).sum();
        double durationSeconds = selected.stream().mapToDouble(Sample::durationSeconds).sum();
        Double pace = distanceKm > 0
                ? PaceFormat.round(durationSeconds / distanceKm, 1)
                : null;
        Double delta = pace == null || baselinePace == null
                ? null
                : PaceFormat.round(pace - baselinePace, 1);
        return new WeatherImpact.Bucket(
                label,
                selected.size(),
                PaceFormat.round(distanceKm, 2),
                pace,
                PaceFormat.pace(pace),
                delta,
                average(selected.stream().map(Sample::temperatureC).toList()),
                average(selected.stream().map(Sample::humidityPercent).toList()),
                average(selected.stream().map(Sample::windSpeedKmh).toList()));
    }

    private static Double average(List<Double> values) {
        List<Double> present = values.stream().filter(value -> value != null).toList();
        return present.isEmpty()
                ? null
                : PaceFormat.round(present.stream().mapToDouble(Double::doubleValue).average().orElse(0d), 1);
    }

    private static Double temperaturePaceCorrelation(List<Sample> samples) {
        List<Sample> valid = samples.stream()
                .filter(sample -> sample.temperatureC() != null)
                .toList();
        if (valid.size() < 3) {
            return null;
        }
        double meanTemperature = valid.stream()
                .mapToDouble(Sample::temperatureC).average().orElse(0d);
        double meanPace = valid.stream()
                .mapToDouble(Sample::pace).average().orElse(0d);
        double covariance = 0d;
        double temperatureVariance = 0d;
        double paceVariance = 0d;
        for (Sample sample : valid) {
            double dx = sample.temperatureC() - meanTemperature;
            double dy = sample.pace() - meanPace;
            covariance += dx * dy;
            temperatureVariance += dx * dx;
            paceVariance += dy * dy;
        }
        if (temperatureVariance <= 1e-9 || paceVariance <= 1e-9) {
            return null;
        }
        return PaceFormat.round(covariance / Math.sqrt(temperatureVariance * paceVariance), 2);
    }

    private static String summaryText(int totalRuns, int sampleSize, Double baselinePace,
                                      List<WeatherImpact.Bucket> temperatureBuckets, Double correlation) {
        if (sampleSize == 0) {
            return "区间内没有同时具备配速与天气记录的跑步，无法分析天气影响；"
                    + "可先调用 reload_data 回填天气。";
        }
        StringBuilder text = new StringBuilder("基于 ").append(sampleSize)
                .append(" 次有天气记录的跑步（区间共 ").append(totalRuns).append(" 次）");
        if (baselinePace != null) {
            text.append("，整体平均配速 ").append(PaceFormat.pace(baselinePace));
        }
        WeatherImpact.Bucket notable = temperatureBuckets.stream()
                .filter(bucket -> bucket.runCount() >= 2 && bucket.paceDeltaSecondsPerKm() != null)
                .max(Comparator.comparingDouble(bucket -> Math.abs(bucket.paceDeltaSecondsPerKm())))
                .orElse(null);
        if (notable != null && Math.abs(notable.paceDeltaSecondsPerKm()) >= 3d) {
            double delta = notable.paceDeltaSecondsPerKm();
            text.append("；").append(notable.label()).append(" 的 ").append(notable.runCount())
                    .append(" 次跑步平均配速比整体")
                    .append(delta >= 0 ? "慢 " : "快 ")
                    .append(Math.abs(Math.round(delta))).append(" 秒/公里");
        }
        if (correlation != null && Math.abs(correlation) >= 0.3d) {
            text.append("；气温与配速相关系数 r=").append(correlation)
                    .append(correlation > 0 ? "（气温越高配速越慢）" : "（气温越高配速越快）");
        }
        if (sampleSize < 3) {
            text.append("；样本较少，结论仅供参考");
        }
        return text.toString();
    }

    /**
     * 一条可用样本：跑步摘要 + 起跑（或结束）天气快照。
     */
    private record Sample(
            RunActivity run,
            double distanceKm,
            double durationSeconds,
            Double temperatureC,
            Double humidityPercent,
            Double windSpeedKmh,
            Double precipitationMm,
            double pace) {
    }
}
