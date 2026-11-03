package org.example.sporthealth.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.example.sporthealth.domain.ActiveDay;
import org.example.sporthealth.domain.BestEffort;
import org.example.sporthealth.domain.BestEfforts;
import org.example.sporthealth.domain.Consistency;
import org.example.sporthealth.domain.DailyRunSummary;
import org.example.sporthealth.domain.ElevationBucket;
import org.example.sporthealth.domain.ElevationProfile;
import org.example.sporthealth.domain.Page;
import org.example.sporthealth.domain.PeriodSummary;
import org.example.sporthealth.domain.PersonalBestEffort;
import org.example.sporthealth.domain.PersonalBests;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunComparison;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.RunDetail;
import org.example.sporthealth.domain.RunFilter;
import org.example.sporthealth.domain.RunLookup;
import org.example.sporthealth.domain.RunSplit;
import org.example.sporthealth.domain.RunSplits;
import org.example.sporthealth.domain.RunSummary;
import org.example.sporthealth.domain.StopPoint;
import org.example.sporthealth.domain.StopPoints;
import org.example.sporthealth.domain.Streak;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrainingTrend;
import org.example.sporthealth.domain.TrendPeriod;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.DateRangeResolver;
import org.example.sporthealth.support.PaceFormat;
import org.example.sporthealth.support.TrackPath;
import org.springframework.stereotype.Service;

/**
 * 基于 TCX 跑步记录的分析：区间汇总、按天汇总、单次详情、个人最佳、两次对比，
 * 以及分段配速、最佳努力（单次与区间）、海拔剖面、停顿检测、训练趋势与连续性。
 *
 * <p>需要轨迹的接口（分段、最佳努力、海拔、停顿）都会按需触发
 * {@link HealthDataRepository#route(String)}，仓储层解析一次后缓存。
 */
@Service
public class RunAnalyticsService {

    private static final double DEFAULT_SPLIT_METERS = 1000d;
    private static final double MIN_ELEVATION_BUCKET_METERS = 10d;
    private static final double MIN_ELEVATION_SMOOTHING_METERS = 0d;
    private static final double DEFAULT_STOP_SECONDS = 30d;
    private static final double DEFAULT_STOP_DISPLACEMENT_METERS = 30d;

    private final HealthDataRepository repository;
    private final DateRangeResolver rangeResolver;

    public RunAnalyticsService(HealthDataRepository repository, DateRangeResolver rangeResolver) {
        this.repository = repository;
        this.rangeResolver = rangeResolver;
    }

    /** 当前数据快照（不含轨迹点）。 */
    public RunDataset dataset() {
        return repository.dataset();
    }

    /** 未指定日期时默认覆盖数据本身的全部区间，避免默认窗口漏掉历史记录。 */
    public DateRange resolveRange(String startDate, String endDate) {
        List<RunActivity> all = repository.dataset().activities();
        LocalDate first = all.stream().map(RunActivity::date).min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = all.stream().map(RunActivity::date).max(Comparator.naturalOrder()).orElse(null);
        return rangeResolver.resolve(startDate, endDate, first, last);
    }

    /** 区间内的记录，按开始时间倒序（新的在前）。 */
    public List<RunActivity> runs(DateRange range) {
        return runs(range, RunFilter.ALL);
    }

    /** 按过滤条件筛选后排序；条件为空时等价于 {@link #runs(DateRange)}。 */
    public List<RunActivity> runs(DateRange range, RunFilter filter) {
        RunFilter effective = filter == null ? RunFilter.ALL : filter;
        return repository.dataset().activities().stream()
                .filter(run -> range.contains(run.date()))
                .filter(effective::matches)
                .sorted(effective.comparator())
                .toList();
    }

    /** 区间内记录的分页视图；排序与 {@link #runs} 一致。 */
    public Page<RunActivity> list(DateRange range, int offset, int limit) {
        return list(range, RunFilter.ALL, offset, limit);
    }

    /** 按过滤条件分页；过滤在分页之前执行。 */
    public Page<RunActivity> list(DateRange range, RunFilter filter, int offset, int limit) {
        return Page.of(runs(range, filter), offset, limit);
    }

    /** 区间汇总；配速由总距离与总时长重新计算，不对单次配速取平均。 */
    public RunSummary summary(DateRange range) {
        List<RunActivity> runs = runs(range);
        double totalDistanceMeters = runs.stream().mapToDouble(RunActivity::distanceMeters).sum();
        double totalDurationSeconds = runs.stream().mapToDouble(RunActivity::durationSeconds).sum();
        double totalClimb = runs.stream().map(RunActivity::climbMeters).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        double totalDescent = runs.stream().map(RunActivity::descentMeters).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        int recordedDays = (int) runs.stream().map(RunActivity::date).distinct().count();

        Double avgPace = totalDistanceMeters > 0
                ? PaceFormat.round(totalDurationSeconds / (totalDistanceMeters / 1000d), 1)
                : null;
        RunActivity best = runs.stream()
                .filter(run -> run.avgPaceSecondsPerKm() != null)
                .min(Comparator.comparingDouble(RunActivity::avgPaceSecondsPerKm))
                .orElse(null);
        RunActivity longest = runs.stream()
                .max(Comparator.comparingDouble(RunActivity::distanceMeters))
                .orElse(null);

        return new RunSummary(
                range.from(),
                range.to(),
                range.dayCount(),
                runs.size(),
                recordedDays,
                PaceFormat.round(totalDistanceMeters / 1000d, 2),
                PaceFormat.round(totalDurationSeconds / 60d, 1),
                PaceFormat.round(totalClimb, 1),
                PaceFormat.round(totalDescent, 1),
                runs.isEmpty() ? null : PaceFormat.round(totalDistanceMeters / 1000d / runs.size(), 2),
                runs.isEmpty() ? null : PaceFormat.round(totalDurationSeconds / 60d / runs.size(), 1),
                avgPace,
                PaceFormat.pace(avgPace),
                best == null ? null : best.avgPaceSecondsPerKm(),
                best == null ? null : best.id(),
                longest == null ? null : longest.id(),
                longest == null ? null : PaceFormat.round(longest.distanceMeters() / 1000d, 2),
                runs.stream().map(RunActivity::date).min(Comparator.naturalOrder()).orElse(null),
                runs.stream().map(RunActivity::date).max(Comparator.naturalOrder()).orElse(null));
    }

    /** 只返回有跑步记录的日期，按时间升序，便于看出训练节奏。 */
    public List<DailyRunSummary> daily(DateRange range) {
        Map<LocalDate, List<RunActivity>> grouped = new LinkedHashMap<>();
        runs(range).stream()
                .sorted(Comparator.comparing(RunActivity::startTime))
                .forEach(run -> grouped.computeIfAbsent(run.date(), key -> new ArrayList<>()).add(run));

        List<DailyRunSummary> result = new ArrayList<>(grouped.size());
        grouped.forEach((date, dayRuns) -> result.add(new DailyRunSummary(
                date,
                dayRuns.size(),
                PaceFormat.round(dayRuns.stream().mapToDouble(RunActivity::distanceMeters).sum() / 1000d, 2),
                PaceFormat.round(dayRuns.stream().mapToDouble(RunActivity::durationSeconds).sum() / 60d, 1),
                paceOf(dayRuns),
                PaceFormat.pace(paceOf(dayRuns)),
                PaceFormat.round(dayRuns.stream().map(RunActivity::climbMeters).filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue).sum(), 1),
                dayRuns.stream().map(RunActivity::id).toList())));
        return result;
    }

    /**
     * 按天 / 周 / 月聚合。{@code groupBy} 无法识别时按天处理。
     * 周以周一为起点，月为自然月；只返回有记录的周期。
     */
    public List<PeriodSummary> grouped(DateRange range, String groupBy) {
        String mode = normalizeGroup(groupBy);
        Map<LocalDate, List<RunActivity>> grouped = new TreeMap<>();
        runs(range).stream()
                .sorted(Comparator.comparing(RunActivity::startTime))
                .forEach(run -> grouped
                        .computeIfAbsent(periodStart(run.date(), mode), key -> new ArrayList<>())
                        .add(run));

        List<PeriodSummary> result = new ArrayList<>(grouped.size());
        grouped.forEach((start, periodRuns) -> {
            LocalDate end = periodEnd(start, mode);
            double distanceMeters = periodRuns.stream().mapToDouble(RunActivity::distanceMeters).sum();
            double durationSeconds = periodRuns.stream().mapToDouble(RunActivity::durationSeconds).sum();
            Double pace = distanceMeters > 0
                    ? PaceFormat.round(durationSeconds / (distanceMeters / 1000d), 1)
                    : null;
            result.add(new PeriodSummary(
                    start,
                    end,
                    label(start, end, mode),
                    periodRuns.size(),
                    PaceFormat.round(distanceMeters / 1000d, 2),
                    PaceFormat.round(durationSeconds / 60d, 1),
                    pace,
                    PaceFormat.pace(pace),
                    PaceFormat.round(periodRuns.stream().map(RunActivity::climbMeters).filter(Objects::nonNull)
                            .mapToDouble(Double::doubleValue).sum(), 1),
                    periodRuns.stream().map(RunActivity::id).toList()));
        });
        return result;
    }

    /**
     * 按 id、文件名或 TCX Activity Id 查找。先精确匹配，再允许唯一前缀/包含匹配。
     *
     * <p>包含匹配命中多条时返回 {@link RunLookup#isAmbiguous()}，不再随机取第一条。
     */
    public RunLookup lookup(String runId) {
        if (runId == null || runId.isBlank()) {
            return RunLookup.notFound();
        }
        String key = runId.trim();
        List<RunActivity> all = repository.dataset().activities();

        List<RunActivity> exact = all.stream()
                .filter(run -> key.equalsIgnoreCase(run.id())
                        || key.equalsIgnoreCase(run.sourceFile())
                        || key.equalsIgnoreCase(run.activityId()))
                .toList();
        if (exact.size() == 1) {
            return RunLookup.found(exact.get(0));
        }
        if (exact.size() > 1) {
            return RunLookup.ambiguous(exact);
        }

        String lowerKey = key.toLowerCase(Locale.ROOT);
        List<RunActivity> partial = all.stream()
                .filter(run -> run.id().toLowerCase(Locale.ROOT).contains(lowerKey))
                .toList();
        if (partial.size() == 1) {
            return RunLookup.found(partial.get(0));
        }
        if (partial.size() > 1) {
            return RunLookup.ambiguous(partial);
        }
        return RunLookup.notFound();
    }

    /** 唯一命中的便捷写法；多义或未找到时返回 empty。 */
    public Optional<RunActivity> find(String runId) {
        RunLookup lookup = lookup(runId);
        return lookup.isFound() ? Optional.of(lookup.run()) : Optional.empty();
    }

    /**
     * 单次详情；{@code trackLimit <= 0} 时不加载轨迹（{@code route} 为 null）。
     * 轨迹在此处才按需解析，列表接口不触碰。
     */
    public RunDetail detail(RunActivity run, int trackOffset, int trackLimit) {
        Page<TrackPoint> route = trackLimit > 0
                ? Page.of(repository.route(run.id()), trackOffset, trackLimit)
                : null;
        return new RunDetail(
                run,
                PaceFormat.pace(run.avgPaceSecondsPerKm()),
                PaceFormat.speed(run.avgSpeedKmh()),
                route);
    }

    /** 区间内个人最佳：最长距离、最快配速、最长时长、最大爬升。 */
    public PersonalBests bests(DateRange range) {
        List<RunActivity> runs = runs(range);
        RunActivity longestDistance = runs.stream()
                .max(Comparator.comparingDouble(RunActivity::distanceMeters))
                .orElse(null);
        RunActivity fastestPace = runs.stream()
                .filter(run -> run.avgPaceSecondsPerKm() != null)
                .min(Comparator.comparingDouble(RunActivity::avgPaceSecondsPerKm))
                .orElse(null);
        RunActivity longestDuration = runs.stream()
                .max(Comparator.comparingDouble(RunActivity::durationSeconds))
                .orElse(null);
        RunActivity mostClimb = runs.stream()
                .filter(run -> run.climbMeters() != null)
                .max(Comparator.comparingDouble(RunActivity::climbMeters))
                .orElse(null);
        return new PersonalBests(range.from(), range.to(), runs.size(),
                longestDistance, fastestPace, longestDuration, mostClimb);
    }

    /**
     * 对比两次记录，差值为 {@code other - base}；配速差为负数表示 other 更快。
     * 任一次缺配速时退化为只比较距离，并在结论里说明。
     */
    public RunComparison compare(RunActivity base, RunActivity other) {
        Double distanceDiffKm = PaceFormat.round(
                (other.distanceMeters() - base.distanceMeters()) / 1000d, 2);
        Double durationDiffMinutes = PaceFormat.round(
                (other.durationSeconds() - base.durationSeconds()) / 60d, 1);
        Double paceDiff = (base.avgPaceSecondsPerKm() == null || other.avgPaceSecondsPerKm() == null)
                ? null
                : PaceFormat.round(other.avgPaceSecondsPerKm() - base.avgPaceSecondsPerKm(), 1);

        String verdict;
        if (paceDiff == null) {
            verdict = "其中一次缺少配速数据，只能比较距离：" + other.id() + " 比 " + base.id()
                    + (distanceDiffKm >= 0 ? " 多 " : " 少 ") + Math.abs(distanceDiffKm) + " 公里";
        } else if (Math.abs(paceDiff) < 1) {
            verdict = "两次配速基本持平（相差不到 1 秒/公里）";
        } else if (paceDiff < 0) {
            verdict = other.id() + " 更快，每公里快 " + Math.round(-paceDiff) + " 秒";
        } else {
            verdict = base.id() + " 更快，每公里快 " + Math.round(paceDiff) + " 秒";
        }
        return new RunComparison(base, other, distanceDiffKm, durationDiffMinutes, paceDiff, verdict);
    }

    /**
     * 按固定距离分段（默认 1 公里）。距离与时间都沿 GPS 轨迹累积并插值，
     * 因此最后一段可能不满整段，用 {@link RunSplit#partial()} 标记。
     *
     * <p>无有效 GPS 时返回空的 {@code splits}，由工具层转成 {@code NO_GPS}。
     */
    public RunSplits splits(RunActivity run, double splitMeters) {
        double step = splitMeters > 0 ? splitMeters : DEFAULT_SPLIT_METERS;
        TrackPath path = TrackPath.of(repository.route(run.id()));
        if (!path.usable()) {
            return new RunSplits(run.id(), run.date(), step, 0d, 0d, null, null, null,
                    List.of(), null, null, null, null, null, null);
        }

        double total = path.totalDistanceMeters();
        List<RunSplit> splits = new ArrayList<>();
        double start = 0d;
        int index = 1;
        while (start < total - 1e-6) {
            double end = Math.min(start + step, total);
            double distanceKm = (end - start) / 1000d;
            double duration = path.timeAtDistance(end) - path.timeAtDistance(start);
            Double pace = distanceKm > 0 && duration > 0
                    ? PaceFormat.round(duration / distanceKm, 1)
                    : null;
            Double gain = gainOrNull(path.altitudesBetween(start, end), 0d);
            boolean partial = end - start < step - 1e-6;
            splits.add(new RunSplit(index++, PaceFormat.round(start / 1000d, 3), PaceFormat.round(end / 1000d, 3),
                    PaceFormat.round(distanceKm, 3), PaceFormat.round(duration, 1), pace, PaceFormat.pace(pace),
                    gain, partial));
            start = end;
        }

        List<RunSplit> full = splits.stream().filter(split -> !split.partial()).toList();
        List<RunSplit> pacePool = full.isEmpty() ? splits : full;
        RunSplit fastest = pacePool.stream()
                .filter(split -> split.paceSecondsPerKm() != null)
                .min(Comparator.comparingDouble(RunSplit::paceSecondsPerKm))
                .orElse(null);
        RunSplit slowest = pacePool.stream()
                .filter(split -> split.paceSecondsPerKm() != null)
                .max(Comparator.comparingDouble(RunSplit::paceSecondsPerKm))
                .orElse(null);

        double half = total / 2d;
        Double firstHalf = halfPace(path, 0d, half);
        Double secondHalf = halfPace(path, half, total);
        Boolean negativeSplit = (firstHalf == null || secondHalf == null)
                ? null
                : secondHalf < firstHalf;
        Double stdDev = paceStdDev(pacePool);

        double avgPace = path.totalSeconds() > 0 && total > 0
                ? PaceFormat.round(path.totalSeconds() / (total / 1000d), 1)
                : null;
        return new RunSplits(run.id(), run.date(), step,
                PaceFormat.round(total / 1000d, 3),
                PaceFormat.round(path.totalSeconds(), 1),
                PaceFormat.duration(path.totalSeconds()),
                avgPace, PaceFormat.pace(avgPace),
                splits, fastest, slowest, firstHalf, secondHalf, negativeSplit, stdDev);
    }

    /**
     * 一次跑步内多个目标距离的最佳成绩（滑窗）。
     *
     * <p>时间轴在相邻采样点之间是线性的，因此固定距离窗口的最小值一定出现在
     * 某个采样点作为起点或终点时，枚举这两类候选即可，无需逐秒扫描。
     *
     * @param targetMeters 目标距离（米），非正数会被忽略，重复值只保留一个
     */
    public BestEfforts bestEfforts(RunActivity run, List<Double> targetMeters) {
        TrackPath path = TrackPath.of(repository.route(run.id()));
        List<BestEffort> efforts = new ArrayList<>();
        List<Double> unavailable = new ArrayList<>();

        for (double target : normalizeTargets(targetMeters)) {
            double[] window = fastestWindow(path, target);
            if (window == null) {
                unavailable.add(target);
                continue;
            }
            efforts.add(toBestEffort(target, window[0], window[1]));
        }
        return new BestEfforts(run.id(), PaceFormat.round(path.totalDistanceMeters() / 1000d, 3),
                efforts, unavailable);
    }

    /**
     * 区间内跨记录的目标距离最佳（例如区间内最快的 1 公里 / 5 公里）。
     *
     * <p>与 {@link #bestEfforts(RunActivity, List)} 互补：后者是“这一次里哪一段最好”，
     * 这里是“哪一次跑出的这一段最好”。需要逐条解析轨迹，没有可用 GPS 或距离不足的记录会被跳过。
     *
     * @param targetMeters 目标距离（米），非正数忽略，重复值只保留一个
     * @return 每个目标距离至多一条，按目标距离升序；全部记录都无法覆盖的目标不出现
     */
    public List<PersonalBestEffort> effortBests(DateRange range, List<Double> targetMeters) {
        List<RunActivity> runs = runs(range);
        List<PersonalBestEffort> result = new ArrayList<>();

        for (double target : normalizeTargets(targetMeters)) {
            RunActivity bestRun = null;
            double bestDuration = Double.POSITIVE_INFINITY;
            double bestStart = 0d;
            for (RunActivity run : runs) {
                TrackPath path = TrackPath.of(repository.route(run.id()));
                double[] window = fastestWindow(path, target);
                if (window != null && window[0] < bestDuration) {
                    bestDuration = window[0];
                    bestStart = window[1];
                    bestRun = run;
                }
            }
            if (bestRun != null) {
                BestEffort effort = toBestEffort(target, bestDuration, bestStart);
                result.add(new PersonalBestEffort(
                        effort.targetMeters(),
                        bestRun.id(),
                        bestRun.date(),
                        effort.durationSeconds(),
                        effort.durationText(),
                        effort.paceSecondsPerKm(),
                        effort.paceText(),
                        effort.startKm(),
                        effort.endKm()));
            }
        }
        return result;
    }

    /**
     * 在轨迹上寻找某个目标距离的最快连续区间。
     *
     * <p>时间轴在相邻采样点之间是线性的，因此固定距离窗口的最小用时一定出现在
     * 某个采样点作为起点或终点时，枚举这两类候选即可，无需逐秒扫描。
     *
     * @return {@code {用时秒, 起点米}}；轨迹不可用或距离不足时返回 null
     */
    private static double[] fastestWindow(TrackPath path, double targetMeters) {
        double total = path.totalDistanceMeters();
        if (!path.usable() || total + 1e-6 < targetMeters) {
            return null;
        }
        double lastStart = total - targetMeters;
        double[] candidates = new double[path.distanceNodes().size() * 2 + 2];
        int cursor = 0;
        candidates[cursor++] = 0d;
        candidates[cursor++] = Math.max(0d, lastStart);
        for (TrackPath.Node node : path.distanceNodes()) {
            double distance = node.distanceMeters();
            if (distance + targetMeters <= total + 1e-6) {
                candidates[cursor++] = distance;
            }
            if (distance - targetMeters >= -1e-6) {
                candidates[cursor++] = Math.max(0d, distance - targetMeters);
            }
        }

        double bestDuration = Double.POSITIVE_INFINITY;
        double bestStart = 0d;
        for (int i = 0; i < cursor; i++) {
            double start = Math.clamp(candidates[i], 0d, lastStart);
            double duration = path.timeAtDistance(start + targetMeters) - path.timeAtDistance(start);
            if (duration >= 0 && duration < bestDuration) {
                bestDuration = duration;
                bestStart = start;
            }
        }
        return Double.isFinite(bestDuration) ? new double[] {bestDuration, bestStart} : null;
    }

    private static BestEffort toBestEffort(double targetMeters, double durationSeconds, double startMeters) {
        double pace = durationSeconds / (targetMeters / 1000d);
        return new BestEffort(targetMeters,
                PaceFormat.round(durationSeconds, 1),
                PaceFormat.duration(durationSeconds),
                PaceFormat.round(pace, 1),
                PaceFormat.pace(pace),
                PaceFormat.round(startMeters / 1000d, 3),
                PaceFormat.round((startMeters + targetMeters) / 1000d, 3));
    }

    /**
     * 海拔剖面：按固定距离分桶，输出平均/最低/最高海拔、桶内爬升下降与平均坡度。
     *
     * @param bucketMeters    桶长度（米），小于 10 时按 10 处理
     * @param smoothingMeters 爬升平滑阈值（米），负数按 0 处理
     */
    public ElevationProfile elevationProfile(RunActivity run, double bucketMeters, double smoothingMeters) {
        double bucket = Math.max(MIN_ELEVATION_BUCKET_METERS, bucketMeters);
        double smoothing = Math.max(MIN_ELEVATION_SMOOTHING_METERS, smoothingMeters);
        TrackPath path = TrackPath.of(repository.route(run.id()));
        double total = path.totalDistanceMeters();

        List<Double> allAltitudes = path.altitudesBetween(0d, total);
        Double minAltitude = allAltitudes.stream().filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        Double maxAltitude = allAltitudes.stream().filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        double[] totalGainLoss = gainLoss(allAltitudes, smoothing);

        List<ElevationBucket> buckets = new ArrayList<>();
        if (path.usable()) {
            double start = 0d;
            while (start < total - 1e-6) {
                double end = Math.min(start + bucket, total);
                List<Double> altitudes = path.altitudesBetween(start, end);
                double[] gainLoss = gainLoss(altitudes, smoothing);
                Double average = altitudes.isEmpty()
                        ? null
                        : PaceFormat.round(altitudes.stream().mapToDouble(Double::doubleValue).average().orElse(0d), 1);
                Double min = altitudes.stream().min(Comparator.naturalOrder()).orElse(null);
                Double max = altitudes.stream().max(Comparator.naturalOrder()).orElse(null);
                double length = end - start;
                Double grade = length > 0 && (gainLoss[0] > 0 || gainLoss[1] > 0)
                        ? PaceFormat.round((gainLoss[0] - gainLoss[1]) / length * 100d, 1)
                        : null;
                buckets.add(new ElevationBucket(
                        PaceFormat.round(start / 1000d, 3),
                        PaceFormat.round(end / 1000d, 3),
                        average == null ? null : PaceFormat.round(average, 1),
                        min == null ? null : PaceFormat.round(min, 1),
                        max == null ? null : PaceFormat.round(max, 1),
                        PaceFormat.round(gainLoss[0], 1),
                        PaceFormat.round(gainLoss[1], 1),
                        grade));
                start = end;
            }
        }

        boolean hasAltitude = minAltitude != null;
        return new ElevationProfile(
                run.id(),
                run.date(),
                bucket,
                smoothing,
                PaceFormat.round(total / 1000d, 3),
                hasAltitude ? PaceFormat.round(totalGainLoss[0], 1) : null,
                hasAltitude ? PaceFormat.round(totalGainLoss[1], 1) : null,
                minAltitude,
                maxAltitude,
                buckets);
    }

    /**
     * 检测轨迹中的停顿：相邻有效坐标点间隔 ≥ {@code minStopSeconds} 且位移 ≤
     * {@code maxDisplacementMeters} 视为一次停顿，连续满足条件的点会合并。
     *
     * @param minStopSeconds           最短停顿秒数，小于 1 时按 30 处理
     * @param maxDisplacementMeters    停顿期间允许的最大位移（米），小于 0 时按 30 处理
     */
    public StopPoints stopPoints(RunActivity run, double minStopSeconds, double maxDisplacementMeters) {
        double minSeconds = minStopSeconds >= 1 ? minStopSeconds : DEFAULT_STOP_SECONDS;
        double maxDisplacement = maxDisplacementMeters >= 0
                ? maxDisplacementMeters : DEFAULT_STOP_DISPLACEMENT_METERS;
        List<TrackPoint> points = repository.route(run.id());

        List<StopPoint> stops = new ArrayList<>();
        TrackPoint previous = null;
        MutableStop current = null;
        for (TrackPoint point : points) {
            if (point == null || point.timestamp() == null || !point.hasPosition()) {
                continue;
            }
            if (previous != null) {
                double seconds = Duration.between(previous.timestamp(), point.timestamp()).toMillis() / 1000d;
                double displacement = TrackPath.haversineMeters(
                        previous.latitude(), previous.longitude(), point.latitude(), point.longitude());
                if (seconds >= minSeconds && displacement <= maxDisplacement) {
                    if (current == null) {
                        current = new MutableStop(previous, point);
                    } else {
                        current.extend(point);
                    }
                } else if (current != null) {
                    stops.add(current.toStopPoint());
                    current = null;
                }
            }
            previous = point;
        }
        if (current != null) {
            stops.add(current.toStopPoint());
        }

        double totalSeconds = stops.stream().mapToDouble(StopPoint::durationSeconds).sum();
        return new StopPoints(run.id(), run.date(), stops.size(),
                PaceFormat.round(totalSeconds, 1),
                PaceFormat.duration(totalSeconds),
                stops);
    }

    /**
     * 按周或按月生成连续周期趋势，最后一个周期以 {@code anchor} 所在周期为准。
     *
     * @param period 周（week）或月（month），无法识别时按周处理
     * @param count  周期数，夹取到 1~52
     * @param anchor 锚点日期，通常取数据集最后一天
     */
    public TrainingTrend trend(String period, int count, LocalDate anchor) {
        boolean monthly = "month".equalsIgnoreCase(period);
        String mode = monthly ? "month" : "week";
        int periods = Math.clamp(count <= 0 ? 4 : count, 1, 52);
        LocalDate base = anchor == null ? LocalDate.now() : anchor;
        LocalDate currentStart = periodStart(base, mode);

        List<TrendPeriod> result = new ArrayList<>(periods);
        TrendPeriod previous = null;
        for (int i = periods - 1; i >= 0; i--) {
            LocalDate start = monthly ? currentStart.minusMonths(i) : currentStart.minusWeeks(i);
            LocalDate end = periodEnd(start, mode);
            RunSummary summary = summary(new DateRange(start, end));
            Double distanceDelta = previous == null || previous.distanceKm() <= 0
                    ? null
                    : PaceFormat.round((summary.totalDistanceKm() - previous.distanceKm())
                            / previous.distanceKm() * 100d, 1);
            Double paceDelta = previous == null || previous.paceSecondsPerKm() == null
                    || summary.avgPaceSecondsPerKm() == null
                    ? null
                    : PaceFormat.round(summary.avgPaceSecondsPerKm() - previous.paceSecondsPerKm(), 1);
            TrendPeriod periodSummary = new TrendPeriod(
                    start,
                    end,
                    label(start, end, mode),
                    summary.runCount(),
                    summary.totalDistanceKm(),
                    summary.totalDurationMinutes(),
                    summary.avgPaceSecondsPerKm(),
                    summary.avgPaceText(),
                    summary.totalClimbMeters(),
                    distanceDelta,
                    paceDelta);
            result.add(periodSummary);
            previous = periodSummary;
        }
        return new TrainingTrend(mode, periods, result, trendText(result));
    }

    /**
     * 连续性与打卡统计。所有计算基于区间内实际有记录的日期，
     * {@code currentStreak} 以最后一次记录为锚点，不受“今天”影响。
     */
    public Consistency consistency(DateRange range) {
        List<RunActivity> runs = runs(range);
        Map<LocalDate, List<RunActivity>> byDate = new TreeMap<>();
        runs.forEach(run -> byDate.computeIfAbsent(run.date(), key -> new ArrayList<>()).add(run));

        List<ActiveDay> days = byDate.entrySet().stream()
                .map(entry -> new ActiveDay(
                        entry.getKey(),
                        entry.getValue().size(),
                        PaceFormat.round(entry.getValue().stream()
                                .mapToDouble(RunActivity::distanceMeters).sum() / 1000d, 2),
                        PaceFormat.round(entry.getValue().stream()
                                .mapToDouble(RunActivity::durationSeconds).sum() / 60d, 1)))
                .toList();

        LocalDate first = days.isEmpty() ? null : days.get(0).date();
        LocalDate last = days.isEmpty() ? null : days.get(days.size() - 1).date();
        Streak longest = longestStreak(days);
        Streak current = currentStreak(days);
        Double averageGap = null;
        if (days.size() >= 2) {
            averageGap = PaceFormat.round(
                    (double) (last.toEpochDay() - first.toEpochDay()) / (days.size() - 1), 2);
        }
        double activeRate = range.dayCount() == 0
                ? 0d
                : PaceFormat.round(days.size() * 100d / range.dayCount(), 1);

        String text = days.isEmpty()
                ? "区间 " + range.text() + " 内没有跑步记录"
                : "区间 " + range.text() + " 内 " + days.size() + "/" + range.dayCount()
                        + " 天有记录（" + activeRate + "%），当前连续 " + current.days()
                        + " 天，最长连续 " + longest.days() + " 天";
        return new Consistency(range.from(), range.to(), days.size(), range.dayCount(), activeRate,
                current, longest, averageGap, last, days, text);
    }

    private Double paceOf(List<RunActivity> runs) {
        double distanceMeters = runs.stream().mapToDouble(RunActivity::distanceMeters).sum();
        double durationSeconds = runs.stream().mapToDouble(RunActivity::durationSeconds).sum();
        return distanceMeters > 0
                ? PaceFormat.round(durationSeconds / (distanceMeters / 1000d), 1)
                : null;
    }

    private Double halfPace(TrackPath path, double fromMeters, double toMeters) {
        double distanceKm = (toMeters - fromMeters) / 1000d;
        double duration = path.timeAtDistance(toMeters) - path.timeAtDistance(fromMeters);
        return distanceKm > 0 && duration > 0 ? PaceFormat.round(duration / distanceKm, 1) : null;
    }

    private Double paceStdDev(List<RunSplit> splits) {
        List<Double> paces = splits.stream()
                .map(RunSplit::paceSecondsPerKm)
                .filter(Objects::nonNull)
                .toList();
        if (paces.size() < 2) {
            return null;
        }
        double mean = paces.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        double variance = paces.stream()
                .mapToDouble(pace -> (pace - mean) * (pace - mean))
                .sum() / paces.size();
        return PaceFormat.round(Math.sqrt(variance), 1);
    }

    private static Double gainOrNull(List<Double> altitudes, double smoothing) {
        if (altitudes.size() < 2) {
            return null;
        }
        return PaceFormat.round(gainLoss(altitudes, smoothing)[0], 1);
    }

    /** 用“参考海拔”法计算爬升/下降，只有变化超过阈值才计入。 */
    private static double[] gainLoss(List<Double> altitudes, double smoothing) {
        double gain = 0d;
        double loss = 0d;
        Double reference = null;
        for (Double altitude : altitudes) {
            if (altitude == null) {
                continue;
            }
            if (reference == null) {
                reference = altitude;
                continue;
            }
            double delta = altitude - reference;
            if (Math.abs(delta) >= smoothing) {
                if (delta > 0) {
                    gain += delta;
                } else {
                    loss -= delta;
                }
                reference = altitude;
            }
        }
        return new double[] {gain, loss};
    }

    private static List<Double> normalizeTargets(List<Double> targetMeters) {
        if (targetMeters == null || targetMeters.isEmpty()) {
            return List.of(1000d, 5000d);
        }
        return targetMeters.stream()
                .filter(Objects::nonNull)
                .filter(target -> target > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private static Streak longestStreak(List<ActiveDay> days) {
        if (days.isEmpty()) {
            return Streak.empty();
        }
        int best = 1;
        LocalDate bestStart = days.get(0).date();
        LocalDate bestEnd = bestStart;
        int run = 1;
        LocalDate runStart = bestStart;
        for (int i = 1; i < days.size(); i++) {
            LocalDate current = days.get(i).date();
            if (current.toEpochDay() - days.get(i - 1).date().toEpochDay() == 1) {
                run++;
            } else {
                run = 1;
                runStart = current;
            }
            if (run > best) {
                best = run;
                bestStart = runStart;
                bestEnd = current;
            }
        }
        return new Streak(best, bestStart, bestEnd);
    }

    private static Streak currentStreak(List<ActiveDay> days) {
        if (days.isEmpty()) {
            return Streak.empty();
        }
        int run = 1;
        LocalDate end = days.get(days.size() - 1).date();
        LocalDate start = end;
        for (int i = days.size() - 1; i > 0; i--) {
            LocalDate current = days.get(i).date();
            LocalDate previous = days.get(i - 1).date();
            if (current.toEpochDay() - previous.toEpochDay() == 1) {
                run++;
                start = previous;
            } else {
                break;
            }
        }
        return new Streak(run, start, end);
    }

    private static String trendText(List<TrendPeriod> periods) {
        if (periods.isEmpty()) {
            return "没有可用于趋势分析的记录";
        }
        TrendPeriod latest = periods.get(periods.size() - 1);
        if (periods.size() == 1) {
            return latest.label() + " 共 " + latest.runCount() + " 次跑步，"
                    + latest.distanceKm() + " 公里";
        }
        TrendPeriod previous = periods.get(periods.size() - 2);
        String distance = latest.distanceDeltaPercent() == null
                ? "上一周期没有记录"
                : (latest.distanceDeltaPercent() >= 0 ? "增加 " : "减少 ")
                        + Math.abs(latest.distanceDeltaPercent()) + "%";
        String pace = latest.paceDeltaSecondsPerKm() == null
                ? "配速无法比较"
                : (latest.paceDeltaSecondsPerKm() < 0 ? "配速变快 " : "配速变慢 ")
                        + Math.abs(Math.round(latest.paceDeltaSecondsPerKm())) + " 秒/公里";
        return latest.label() + " 相比上一周期（" + previous.label() + "），跑量" + distance
                + "，" + pace;
    }

    private static String normalizeGroup(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "day";
        }
        String key = groupBy.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "week", "month" -> key;
            default -> "day";
        };
    }

    private static LocalDate periodStart(LocalDate date, String mode) {
        return switch (mode) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private static LocalDate periodEnd(LocalDate start, String mode) {
        return switch (mode) {
            case "week" -> start.plusDays(6);
            case "month" -> start.withDayOfMonth(start.lengthOfMonth());
            default -> start;
        };
    }

    private static String label(LocalDate start, LocalDate end, String mode) {
        return switch (mode) {
            case "month" -> start.getYear() + "-" + String.format("%02d", start.getMonthValue());
            case "week" -> start + " ~ " + end;
            default -> start.toString();
        };
    }

    /** 停顿合并期间的可变累加器。 */
    private static final class MutableStop {
        private final java.time.LocalDateTime startTime;
        private java.time.LocalDateTime endTime;
        private final Double latitude;
        private final Double longitude;
        private final Double altitudeMeters;

        private MutableStop(TrackPoint first, TrackPoint second) {
            this.startTime = first.timestamp();
            this.endTime = second.timestamp();
            this.latitude = second.latitude();
            this.longitude = second.longitude();
            this.altitudeMeters = second.altitudeMeters();
        }

        private void extend(TrackPoint point) {
            this.endTime = point.timestamp();
        }

        private StopPoint toStopPoint() {
            double seconds = Duration.between(startTime, endTime).toMillis() / 1000d;
            return new StopPoint(startTime, endTime, PaceFormat.round(seconds, 1),
                    PaceFormat.duration(seconds), latitude, longitude, altitudeMeters);
        }
    }
}
