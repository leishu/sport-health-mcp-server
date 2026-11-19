package org.example.sporthealth.service;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.DynamicsSegment;
import org.example.sporthealth.domain.DynamicsSummary;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunningDynamics;
import org.example.sporthealth.domain.RunningDynamicsDetail;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.support.HuaweiSummaryData;
import org.example.sporthealth.support.PaceFormat;
import org.example.sporthealth.support.TrackPath;
import org.springframework.stereotype.Service;

/**
 * 跑姿与步幅：触地时间、垂直振幅、左右平衡、步幅、步频的汇总、分段与趋势。
 *
 * <p>数据分两处，各有取舍：
 * <ul>
 *   <li>{@code run_activity.summary_data} 里的跑姿块是华为自己算好的整次均值，读它不用碰轨迹，
 *       区间汇总（几十上百条记录）走这条路；</li>
 *   <li>{@code track_stream} 的 {@code rp} 流是每 5 秒一个的原始采样，单次的分段、前后半程对比
 *       必须用它。它只带 {@code k}（相对起跑的<b>秒数</b>）与 {@code gct/vo/gtb}，
 *       没有距离，所以要用 {@link TrackPath#distanceAtTime} 落回距离轴。</li>
 * </ul>
 *
 * <p>步幅没有现成字段：整次用「距离 ÷ 总步数」（华为给的步数是精确值），分段/半程用
 * 「本段速度 × 60 ÷ 本段平均步频」——分段里没有单独步数，只能这样折算。
 */
@Service
@Slf4j
public class RunningDynamicsService {

    /** 跑姿流类型。 */
    private static final String STREAM_TYPE_RP = "rp";
    /** 跑姿流里的字段名：触地时间（ms）、垂直振幅（cm）、左右平衡（%）。 */
    private static final String FIELD_GROUND_CONTACT_TIME = "gct";
    private static final String FIELD_VERTICAL_OSCILLATION = "vo";
    private static final String FIELD_GROUND_BALANCE = "gtb";

    private static final String GROUP_WEEK = "week";
    private static final String GROUP_MONTH = "month";

    private final HealthDataRepository repository;

    public RunningDynamicsService(HealthDataRepository repository) {
        this.repository = repository;
    }

    /**
     * 区间内各次记录的跑姿汇总：只读 {@code run_activity}（summaryData + 步数），不读轨迹与原始流。
     *
     * @return 至少有一项指标（跑姿或步幅）的记录，顺序与入参一致
     */
    public List<RunningDynamics> ofRuns(List<RunActivity> runs) {
        List<RunningDynamics> rows = new ArrayList<>();
        for (RunActivity run : runs) {
            RunningDynamics row = ofRun(run);
            if (row.hasAnyMetric()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 单次记录的整次汇总；没有跑姿也没有步数时各项为 null。 */
    public RunningDynamics ofRun(RunActivity run) {
        Map<String, Object> posture = HuaweiSummaryData.posture(run.summaryData());
        return new RunningDynamics(run.id(), run.date(),
                PaceFormat.round(run.distanceMeters() / 1000d, 3),
                run.avgPaceSecondsPerKm(), PaceFormat.pace(run.avgPaceSecondsPerKm()),
                run.avgCadence(), stride(run),
                number(posture.get("groundContactTimeMs")),
                number(posture.get("verticalOscillationCm")),
                number(posture.get("groundBalancePercent")),
                0);
    }

    /**
     * 单次详情：整次汇总 + 按距离分段 + 前后半程对比。
     *
     * <p>汇总值优先用华为的 summaryData（官方口径）；某些记录只有 {@code rp} 流、没有跑姿块时，
     * 退回用采样均值补上，不会因此整条记录变成「没有跑姿数据」。
     *
     * @param splitMeters 分段距离（米），调用方保证为正数
     */
    public RunningDynamicsDetail detail(RunActivity run, double splitMeters) {
        List<TrackStream> raw = repository.streams(run.id(), STREAM_TYPE_RP);
        TrackPath path = TrackPath.of(repository.route(run.id()));
        List<Sample> samples = path.usable() ? locate(raw, path) : List.of();

        RunningDynamics headline = ofRun(run);
        if (!headline.hasPosture() && !samples.isEmpty()) {
            headline = withSampleAverages(headline, samples);
        } else if (!samples.isEmpty()) {
            headline = withSampleCount(headline, samples.size());
        }
        if (!path.usable()) {
            return new RunningDynamicsDetail(headline, splitMeters, List.of(), null, null);
        }

        List<DynamicsSegment> segments = new ArrayList<>();
        double total = path.totalDistanceMeters();
        int index = 1;
        for (double start = 0d; start < total - 1e-6; start += splitMeters) {
            double end = Math.min(start + splitMeters, total);
            boolean partial = end - start < splitMeters - 1e-6;
            segments.add(segment(path, samples, start, end, index++, partial));
        }
        double half = total / 2d;
        DynamicsSegment firstHalf = segment(path, samples, 0d, half, 1, false);
        DynamicsSegment secondHalf = segment(path, samples, half, total, 2, false);
        return new RunningDynamicsDetail(headline, splitMeters, segments, firstHalf, secondHalf);
    }

    /** 区间汇总：每条记录算一个样本，先算整次均值再做统计，不让长跑的采样行数压过短跑。 */
    public DynamicsSummary summarize(List<RunningDynamics> rows) {
        List<Double> gct = rows.stream().map(RunningDynamics::groundContactTimeMs)
                .filter(Objects::nonNull).toList();
        List<Double> vo = rows.stream().map(RunningDynamics::verticalOscillationCm)
                .filter(Objects::nonNull).toList();
        List<Double> gtb = rows.stream().map(RunningDynamics::groundBalancePercent)
                .filter(Objects::nonNull).toList();
        List<Double> stride = rows.stream().map(RunningDynamics::strideLengthMeters)
                .filter(Objects::nonNull).toList();
        List<Double> cadence = rows.stream().map(RunningDynamics::cadenceSpm)
                .filter(Objects::nonNull).map(Integer::doubleValue).toList();
        int postureRunCount = (int) rows.stream().filter(RunningDynamics::hasPosture).count();
        return new DynamicsSummary(rows.size(), postureRunCount, stride.size(),
                stat(gct), stat(vo), stat(gtb), stat(stride), stat(cadence));
    }

    /**
     * 按周或月聚合，周期升序。只返回有记录的周期；周期内多次记录取等权平均。
     *
     * @param groupBy {@code week} 或 {@code month}，其它值按 {@code month} 处理（调用方已校验）
     */
    public List<DynamicsSummary.Period> trend(List<RunningDynamics> rows, String groupBy) {
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean weekly = GROUP_WEEK.equalsIgnoreCase(groupBy);
        WeekFields iso = WeekFields.ISO;
        Map<String, Bucket> buckets = new TreeMap<>();
        for (RunningDynamics row : rows) {
            LocalDate date = row.date();
            if (date == null) {
                continue;
            }
            String key;
            LocalDate from;
            LocalDate to;
            if (weekly) {
                from = date.with(iso.dayOfWeek(), 1);
                to = from.plusDays(6);
                key = String.format(Locale.ROOT, "%d-W%02d", from.get(iso.weekBasedYear()), from.get(iso.weekOfWeekBasedYear()));
            } else {
                from = date.withDayOfMonth(1);
                to = from.plusMonths(1).minusDays(1);
                key = String.format(Locale.ROOT, "%d-%02d", date.getYear(), date.getMonthValue());
            }
            buckets.computeIfAbsent(key, ignored -> new Bucket(from, to)).add(row);
        }
        List<DynamicsSummary.Period> periods = new ArrayList<>(buckets.size());
        buckets.forEach((key, bucket) -> periods.add(bucket.toPeriod(key)));
        return periods;
    }

    /** 整次步幅：距离 ÷ 步数。步数为 0 或缺失时返回 null，不用配速猜。 */
    private static Double stride(RunActivity run) {
        Integer steps = run.totalSteps();
        if (steps == null || steps <= 0 || run.distanceMeters() <= 0) {
            return null;
        }
        return PaceFormat.round(run.distanceMeters() / steps, 3);
    }

    /**
     * 跑姿采样落回距离轴。
     *
     * <p>{@code rp} 的 {@code k} 是「相对起跑的秒数」（实测 63 次记录里 {@code max(k) / 总时长}
     * 都在 0.998~1.0），不是轨迹点序号——轨迹点是每 2 秒一个，序号只到时长的一半。没有 {@code k}
     * 的行无法定位，直接跳过。
     */
    private static List<Sample> locate(List<TrackStream> rows, TrackPath path) {
        List<Sample> samples = new ArrayList<>(rows.size());
        for (TrackStream row : rows) {
            if (row.pointIndex() == null) {
                continue;
            }
            double seconds = row.pointIndex();
            Map<String, String> fields = row.fields() == null ? Map.of() : row.fields();
            samples.add(new Sample(path.distanceAtTime(seconds),
                    number(fields.get(FIELD_GROUND_CONTACT_TIME)),
                    number(fields.get(FIELD_VERTICAL_OSCILLATION)),
                    number(fields.get(FIELD_GROUND_BALANCE))));
        }
        return samples;
    }

    /** 只补采样行数（整次汇总值仍用华为官方口径）。 */
    private static RunningDynamics withSampleCount(RunningDynamics headline, int sampleCount) {
        return new RunningDynamics(headline.runId(), headline.date(), headline.distanceKm(),
                headline.paceSecondsPerKm(), headline.paceText(), headline.cadenceSpm(),
                headline.strideLengthMeters(), headline.groundContactTimeMs(),
                headline.verticalOscillationCm(), headline.groundBalancePercent(), sampleCount);
    }

    /** 只有 rp 流、没有 summaryData 跑姿块时的兜底：用采样均值补整次汇总。 */    private static RunningDynamics withSampleAverages(RunningDynamics headline, List<Sample> samples) {
        List<Double> gct = samples.stream().map(Sample::groundContactTimeMs).filter(Objects::nonNull).toList();
        List<Double> vo = samples.stream().map(Sample::verticalOscillationCm).filter(Objects::nonNull).toList();
        List<Double> gtb = samples.stream().map(Sample::groundBalancePercent).filter(Objects::nonNull).toList();
        if (gct.isEmpty() && vo.isEmpty() && gtb.isEmpty()) {
            return headline;
        }
        return new RunningDynamics(headline.runId(), headline.date(), headline.distanceKm(),
                headline.paceSecondsPerKm(), headline.paceText(), headline.cadenceSpm(),
                headline.strideLengthMeters(), mean(gct), mean(vo), mean(gtb), samples.size());
    }

    /** 切一段：配速与步频来自轨迹，跑姿三项来自落在段内的采样点。 */
    private static DynamicsSegment segment(TrackPath path, List<Sample> samples,
                                           double fromMeters, double toMeters, int index, boolean partial) {
        double distance = Math.max(0d, toMeters - fromMeters);
        double seconds = Math.max(0d, path.timeAtDistance(toMeters) - path.timeAtDistance(fromMeters));
        Double pace = distance > 0 && seconds > 0 ? PaceFormat.round(seconds / (distance / 1000d), 1) : null;
        Integer cadence = path.averageCadenceBetween(fromMeters, toMeters);
        Double stride = strideOf(distance, seconds, cadence);

        List<Double> gct = new ArrayList<>();
        List<Double> vo = new ArrayList<>();
        List<Double> gtb = new ArrayList<>();
        int sampleCount = 0;
        for (Sample sample : samples) {
            if (sample.distanceMeters() < fromMeters - 1e-6 || sample.distanceMeters() >= toMeters - 1e-6) {
                continue;
            }
            sampleCount++;
            if (sample.groundContactTimeMs() != null) {
                gct.add(sample.groundContactTimeMs());
            }
            if (sample.verticalOscillationCm() != null) {
                vo.add(sample.verticalOscillationCm());
            }
            if (sample.groundBalancePercent() != null) {
                gtb.add(sample.groundBalancePercent());
            }
        }
        return new DynamicsSegment(index,
                PaceFormat.round(fromMeters / 1000d, 3), PaceFormat.round(toMeters / 1000d, 3),
                PaceFormat.round(distance / 1000d, 3), PaceFormat.round(seconds, 1),
                pace, PaceFormat.pace(pace), cadence, stride,
                mean(gct), mean(vo), mean(gtb), sampleCount, partial);
    }

    /** 分段步幅：速度（m/s）× 60 ÷ 步频（步/分）。缺步频或时长为 0 时返回 null。 */
    private static Double strideOf(double distanceMeters, double seconds, Integer cadence) {
        if (cadence == null || cadence <= 0 || distanceMeters <= 0 || seconds <= 0) {
            return null;
        }
        return PaceFormat.round(distanceMeters / seconds * 60d / cadence, 3);
    }

    private static DynamicsSummary.MetricStat stat(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0d;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double value : values) {
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new DynamicsSummary.MetricStat(values.size(),
                PaceFormat.round(sum / values.size(), 2),
                PaceFormat.round(min, 2), PaceFormat.round(max, 2));
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0d;
        for (double value : values) {
            sum += value;
        }
        return PaceFormat.round(sum / values.size(), 2);
    }

    /** 数字字段可能是字符串也可能是数字，非数字与 {@code -1}/{@code -101} 占位一律当缺省。 */
    private static Double number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < 0d || Math.abs(parsed) < 1e-9) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            return Double.isFinite(parsed) && parsed > 0d ? parsed : null;
        }
        return value == null ? null : number(String.valueOf(value));
    }

    /** 一个跑姿采样点：已经换算成累计距离。 */
    private record Sample(double distanceMeters, Double groundContactTimeMs,
                          Double verticalOscillationCm, Double groundBalancePercent) {
    }

    /** 趋势里的一格，边累加边算均值。 */
    private static final class Bucket {
        private final LocalDate from;
        private final LocalDate to;
        private int runCount;
        private final List<Double> gct = new ArrayList<>();
        private final List<Double> vo = new ArrayList<>();
        private final List<Double> gtb = new ArrayList<>();
        private final List<Double> stride = new ArrayList<>();
        private final List<Double> cadence = new ArrayList<>();

        private Bucket(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }

        private void add(RunningDynamics row) {
            runCount++;
            if (row.groundContactTimeMs() != null) {
                gct.add(row.groundContactTimeMs());
            }
            if (row.verticalOscillationCm() != null) {
                vo.add(row.verticalOscillationCm());
            }
            if (row.groundBalancePercent() != null) {
                gtb.add(row.groundBalancePercent());
            }
            if (row.strideLengthMeters() != null) {
                stride.add(row.strideLengthMeters());
            }
            if (row.cadenceSpm() != null) {
                cadence.add((double) row.cadenceSpm());
            }
        }

        private DynamicsSummary.Period toPeriod(String key) {
            return new DynamicsSummary.Period(key, from, to, runCount,
                    mean(gct), mean(vo), mean(gtb), mean(stride), mean(cadence));
        }
    }
}
