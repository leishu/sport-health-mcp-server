package org.example.sporthealth.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import org.example.sporthealth.domain.RacePrediction;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.RunClassification;
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
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.domain.TrainingMix;
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

    /** Riegel 公式的经验指数，用于把较短距离成绩外推到更长距离。 */
    private static final double RIEGEL_EXPONENT = 1.06d;
    /** 计算最佳努力时额外包含 1 公里，让资料很少时也能有一个参考点。 */
    private static final List<Double> RACE_REFERENCE_TARGETS =
            List.of(1000d, 5000d, 10000d, 21097.5d, 42195d);
    /** 默认输出的预测距离：5K、10K、半马、全马。 */
    private static final List<Double> DEFAULT_RACE_TARGETS =
            List.of(5000d, 10000d, 21097.5d, 42195d);
    /** Minetti 跑步能量代价公式在平地上的值（J/kg/m）。 */
    private static final double MINETTI_FLAT_COST = 3.6d;

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
                    List.of(), null, null, null, null, null, null, null, null);
        }

        double total = path.totalDistanceMeters();
        List<RunSplit> splits = new ArrayList<>();
        double start = 0d;
        int index = 1;
        // 坡度修正配速的全程汇总：用「等效平路距离」加权，而不是对每段配速做算术平均
        double equivalentFlatMeters = 0d;
        double gapCoveredSeconds = 0d;
        boolean hasAltitude = false;
        while (start < total - 1e-6) {
            double end = Math.min(start + step, total);
            double segmentMeters = Math.max(0d, end - start);
            double distanceKm = segmentMeters / 1000d;
            double duration = path.timeAtDistance(end) - path.timeAtDistance(start);
            Double pace = distanceKm > 0 && duration > 0
                    ? PaceFormat.round(duration / distanceKm, 1)
                    : null;
            Double gain = gainOrNull(path.altitudesBetween(start, end), 0d);

            Double startAltitude = path.altitudeAtDistance(start);
            Double endAltitude = path.altitudeAtDistance(end);
            Double netElevation = null;
            Double gradePercent = null;
            Double gapPace = null;
            if (startAltitude != null && endAltitude != null && segmentMeters > 0) {
                hasAltitude = true;
                netElevation = PaceFormat.round(endAltitude - startAltitude, 1);
                gradePercent = PaceFormat.round(netElevation / segmentMeters * 100d, 1);
                if (pace != null) {
                    double ratio = minettiCost(netElevation / segmentMeters) / MINETTI_FLAT_COST;
                    gapPace = PaceFormat.round(pace / ratio, 1);
                    equivalentFlatMeters += segmentMeters * ratio;
                    gapCoveredSeconds += duration;
                }
            }

            boolean partial = segmentMeters < step - 1e-6;
            splits.add(new RunSplit(index++, PaceFormat.round(start / 1000d, 3), PaceFormat.round(end / 1000d, 3),
                    PaceFormat.round(distanceKm, 3), PaceFormat.round(duration, 1), pace, PaceFormat.pace(pace),
                    gain, netElevation, gradePercent, gapPace, PaceFormat.pace(gapPace), partial));
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
        Double avgGapPace = hasAltitude && equivalentFlatMeters > 0 && gapCoveredSeconds > 0
                ? PaceFormat.round(gapCoveredSeconds / (equivalentFlatMeters / 1000d), 1)
                : null;
        return new RunSplits(run.id(), run.date(), step,
                PaceFormat.round(total / 1000d, 3),
                PaceFormat.round(path.totalSeconds(), 1),
                PaceFormat.duration(path.totalSeconds()),
                avgPace, PaceFormat.pace(avgPace),
                splits, fastest, slowest, firstHalf, secondHalf, negativeSplit, stdDev,
                avgGapPace, PaceFormat.pace(avgGapPace));
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

    /**
     * 基于区间内最佳努力预测比赛成绩。参考成绩取 1K / 5K / 10K / 半马 / 全马中距离目标最近的一项，
     * 用 Riegel 公式外推；目标距离本身有实测成绩时直接返回实测值。
     *
     * @param range            统计区间
     * @param targetDistanceKm 指定目标距离（公里），为空时输出 5K / 10K / 半马 / 全马
     */
    public RacePrediction predictRace(DateRange range, Double targetDistanceKm) {
        List<PersonalBestEffort> references = effortBests(range, RACE_REFERENCE_TARGETS);
        if (references.isEmpty()) {
            return new RacePrediction(range.from(), range.to(), "riegel", RIEGEL_EXPONENT,
                    List.of(), List.of(), "区间内没有可用的 GPS 轨迹，或单次距离不足 1 公里，无法做成绩预测");
        }
        List<Double> targets = targetDistanceKm != null && targetDistanceKm > 0
                ? List.of(targetDistanceKm * 1000d)
                : DEFAULT_RACE_TARGETS;
        List<RacePrediction.Prediction> predictions = new ArrayList<>();
        for (double target : targets) {
            RacePrediction.Prediction prediction = predictOne(target, references);
            if (prediction != null) {
                predictions.add(prediction);
            }
        }
        return new RacePrediction(range.from(), range.to(), "riegel", RIEGEL_EXPONENT,
                references, predictions, racePredictionText(predictions));
    }

    private static RacePrediction.Prediction predictOne(double targetMeters,
                                                        List<PersonalBestEffort> references) {
        PersonalBestEffort exact = references.stream()
                .filter(reference -> Math.abs(reference.targetMeters() - targetMeters) < 1d)
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return prediction(targetMeters, exact.durationSeconds(), "ACTUAL", "HIGH", exact);
        }

        PersonalBestEffort reference = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (PersonalBestEffort candidate : references) {
            if (candidate.targetMeters() <= 0 || candidate.durationSeconds() <= 0) {
                continue;
            }
            double score = Math.abs(Math.log(targetMeters / candidate.targetMeters()));
            if (score < bestScore - 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9
                            && reference != null
                            && candidate.targetMeters() > reference.targetMeters())) {
                bestScore = score;
                reference = candidate;
            }
        }
        if (reference == null) {
            return null;
        }
        double predictedSeconds = reference.durationSeconds()
                * Math.pow(targetMeters / reference.targetMeters(), RIEGEL_EXPONENT);
        return prediction(targetMeters, predictedSeconds, "RIEGEL",
                predictionConfidence(targetMeters, reference.targetMeters()), reference);
    }

    private static RacePrediction.Prediction prediction(double targetMeters, double seconds,
                                                        String source, String confidence,
                                                        PersonalBestEffort reference) {
        double pace = seconds / (targetMeters / 1000d);
        return new RacePrediction.Prediction(
                targetMeters,
                PaceFormat.round(targetMeters / 1000d, 3),
                source,
                PaceFormat.round(seconds, 1),
                PaceFormat.duration(seconds),
                PaceFormat.round(pace, 1),
                PaceFormat.pace(pace),
                confidence,
                reference.targetMeters(),
                reference.runId(),
                reference.date(),
                PaceFormat.round(reference.durationSeconds(), 1));
    }

    /** 参考距离与目标距离差得越远，外推误差越大。 */
    private static String predictionConfidence(double targetMeters, double referenceMeters) {
        double ratio = Math.max(targetMeters, referenceMeters) / Math.min(targetMeters, referenceMeters);
        if (ratio <= 1.5d) {
            return "HIGH";
        }
        if (ratio <= 2.5d && referenceMeters >= 3000d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String racePredictionText(List<RacePrediction.Prediction> predictions) {
        if (predictions.isEmpty()) {
            return "没有足够的参考成绩，无法预测";
        }
        RacePrediction.Prediction headline = predictions.stream()
                .filter(prediction -> Math.abs(prediction.targetMeters() - 10000d) < 1d)
                .findFirst()
                .orElse(predictions.get(0));
        return "基于 " + raceDistanceLabel(headline.referenceMeters()) + " 最佳 "
                + PaceFormat.duration(headline.referenceDurationSeconds())
                + "（" + headline.referenceDate() + "），预测 "
                + raceDistanceLabel(headline.targetMeters()) + " 约 "
                + headline.predictedText() + "（" + headline.paceText() + "）";
    }

    private static String raceDistanceLabel(double meters) {
        if (Math.abs(meters - 1000d) < 1d) {
            return "1 公里";
        }
        if (Math.abs(meters - 5000d) < 1d) {
            return "5 公里";
        }
        if (Math.abs(meters - 10000d) < 1d) {
            return "10 公里";
        }
        if (Math.abs(meters - 21097.5d) < 1d) {
            return "半程马拉松";
        }
        if (Math.abs(meters - 42195d) < 1d) {
            return "全程马拉松";
        }
        return PaceFormat.round(meters / 1000d, 2) + " 公里";
    }

    /**
     * 训练负荷与 ACWR。急性窗口 7 天、慢性窗口 28 天，负荷口径可选距离或时长。
     *
     * <p>没有心率时，距离/时长是最稳定的替代指标；{@code confidence} 会明确标出数据覆盖不足，
     * 避免用短期数据给出确定的风险结论。
     *
     * @param anchor 评估截止日期（含），为空时取数据集最后一天
     * @param metric {@code distance}（默认）或 {@code duration}
     */
    public TrainingLoad trainingLoad(LocalDate anchor, String metric) {
        List<RunActivity> all = repository.dataset().activities();
        LocalDate end = anchor != null ? anchor : all.stream()
                .map(RunActivity::date).max(Comparator.naturalOrder()).orElse(LocalDate.now());
        String normalized = "duration".equalsIgnoreCase(metric) ? "duration" : "distance";

        List<RunActivity> chronicRuns = runs(new DateRange(end.minusDays(27), end));
        List<RunActivity> acuteRuns = chronicRuns.stream()
                .filter(run -> !run.date().isBefore(end.minusDays(6)))
                .toList();
        List<RunActivity> previousRuns = runs(new DateRange(end.minusDays(13), end.minusDays(7)));

        TrainingLoad.LoadWindow acute = loadWindow(acuteRuns, 7, normalized);
        TrainingLoad.LoadWindow chronic = loadWindow(chronicRuns, 28, normalized);
        double chronicWeekly = PaceFormat.round(chronic.load() / 4d, 2);
        Double acwr = chronicWeekly > 0
                ? PaceFormat.round(acute.load() / chronicWeekly, 2)
                : null;

        LocalDate firstDate = all.stream().map(RunActivity::date)
                .min(Comparator.naturalOrder()).orElse(null);
        int coverageDays = 0;
        if (firstDate != null && !firstDate.isAfter(end)) {
            LocalDate coverageStart = firstDate.isBefore(end.minusDays(27))
                    ? end.minusDays(27) : firstDate;
            coverageDays = (int) (end.toEpochDay() - coverageStart.toEpochDay()) + 1;
        }
        boolean dataSufficient = coverageDays >= 21 && chronic.trainingDays() >= 7;
        String confidence = coverageDays >= 28 && chronic.trainingDays() >= 14
                ? "HIGH" : dataSufficient ? "MEDIUM" : "LOW";
        String riskLevel = riskLevel(acwr, chronic.load());

        double previous = previousLoad(previousRuns, normalized);
        Double weeklyRamp = previous > 0
                ? PaceFormat.round((acute.load() - previous) / previous * 100d, 1)
                : null;

        int consecutiveDays = consecutiveTrainingDays(all, end);
        int restDays = Math.max(0, 7 - acute.trainingDays());
        Map<LocalDate, Double> dailyLoads = new HashMap<>();
        for (RunActivity run : acuteRuns) {
            dailyLoads.merge(run.date(), loadOf(run, normalized), Double::sum);
        }
        Double monotony = monotony(dailyLoads, end.minusDays(6), end);
        Double strain = monotony == null ? null : PaceFormat.round(acute.load() * monotony, 1);

        String recommendation = recommendation(riskLevel, confidence, coverageDays, weeklyRamp);
        String unit = "duration".equals(normalized) ? "分钟" : "公里";
        String text = "截至 " + end + "，近 7 天负荷 " + acute.load() + " " + unit
                + "，慢性周均 " + chronicWeekly + " " + unit
                + (acwr == null ? "，ACWR 无法计算" : "，ACWR " + acwr + "（" + riskText(riskLevel) + "）")
                + (weeklyRamp == null ? "" : "；最近一周比前一周"
                        + (weeklyRamp >= 0 ? "增加 " : "减少 ") + Math.abs(weeklyRamp) + "%");

        return new TrainingLoad(end, normalized, acute, chronic, chronicWeekly, acwr,
                coverageDays, dataSufficient, confidence, riskLevel, weeklyRamp,
                consecutiveDays, restDays, monotony, strain, recommendation, text);
    }

    private static TrainingLoad.LoadWindow loadWindow(List<RunActivity> runs, int days, String metric) {
        double distanceKm = PaceFormat.round(runs.stream()
                .mapToDouble(RunActivity::distanceMeters).sum() / 1000d, 2);
        double durationMinutes = PaceFormat.round(runs.stream()
                .mapToDouble(RunActivity::durationSeconds).sum() / 60d, 1);
        double load = "duration".equals(metric) ? durationMinutes : distanceKm;
        int trainingDays = (int) runs.stream().map(RunActivity::date).distinct().count();
        return new TrainingLoad.LoadWindow(days, runs.size(), trainingDays,
                distanceKm, durationMinutes, PaceFormat.round(load, 1));
    }

    private static double previousLoad(List<RunActivity> runs, String metric) {
        double distanceKm = runs.stream().mapToDouble(RunActivity::distanceMeters).sum() / 1000d;
        double durationMinutes = runs.stream().mapToDouble(RunActivity::durationSeconds).sum() / 60d;
        return "duration".equals(metric) ? durationMinutes : distanceKm;
    }

    private static double loadOf(RunActivity run, String metric) {
        return "duration".equals(metric) ? run.durationSeconds() / 60d : run.distanceMeters() / 1000d;
    }

    private static int consecutiveTrainingDays(List<RunActivity> all, LocalDate end) {
        Set<LocalDate> dates = new HashSet<>();
        for (RunActivity run : all) {
            dates.add(run.date());
        }
        int count = 0;
        LocalDate cursor = end;
        while (dates.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /** 7 天每日负荷的均值/标准差；休息日为 0 也参与计算。 */
    private static Double monotony(Map<LocalDate, Double> dailyLoads, LocalDate from, LocalDate to) {
        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        if (days < 2) {
            return null;
        }
        double mean = 0d;
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            values[i] = dailyLoads.getOrDefault(from.plusDays(i), 0d);
            mean += values[i];
        }
        mean /= days;
        if (mean <= 1e-9) {
            return null;
        }
        double variance = 0d;
        for (double value : values) {
            variance += (value - mean) * (value - mean);
        }
        double stdDev = Math.sqrt(variance / days);
        return stdDev <= 1e-9 ? null : PaceFormat.round(mean / stdDev, 2);
    }

    private static String riskLevel(Double acwr, double chronicLoad) {
        if (chronicLoad <= 0 || acwr == null) {
            return "INSUFFICIENT_DATA";
        }
        if (acwr < 0.8d) {
            return "UNDERTRAINING";
        }
        if (acwr <= 1.3d) {
            return "OPTIMAL";
        }
        if (acwr <= 1.5d) {
            return "CAUTION";
        }
        return "HIGH_RISK";
    }

    private static String riskText(String riskLevel) {
        return switch (riskLevel) {
            case "UNDERTRAINING" -> "偏低";
            case "OPTIMAL" -> "正常";
            case "CAUTION" -> "偏高";
            case "HIGH_RISK" -> "高风险";
            default -> "数据不足";
        };
    }

    private static String recommendation(String riskLevel, String confidence, int coverageDays,
                                         Double weeklyRamp) {
        String base = switch (riskLevel) {
            case "UNDERTRAINING" -> "近期负荷低于慢性基线，可能处于减量或恢复期；若目标是提升，可每周增加 10-15% 跑量。";
            case "OPTIMAL" -> "负荷在安全区间，维持当前节奏，注意保留至少 1-2 个休息日。";
            case "CAUTION" -> "周负荷增长偏快，本周建议不再加量，优先睡眠与恢复。";
            case "HIGH_RISK" -> "ACWR 超过 1.5，伤病风险升高；建议本周减量 20-30%，并增加休息日。";
            default -> "慢性负荷样本不足，先保持 3-4 周稳定训练，再评估 ACWR 更有意义。";
        };
        if ("LOW".equals(confidence)) {
            base = "数据只覆盖 " + coverageDays + " 天，ACWR 仅供参考。" + base;
        }
        if (weeklyRamp != null && weeklyRamp > 30d && !"HIGH_RISK".equals(riskLevel)) {
            base += " 本周跑量比上周增加 " + weeklyRamp + "%，即使 ACWR 正常也建议控制增幅。";
        }
        return base;
    }

    /**
     * 单次跑步的训练类型识别。没有心率，规则基于距离、时长、配速相对个人中位数与分段波动。
     */
    public RunClassification classify(RunActivity run) {
        List<RunActivity> all = repository.dataset().activities();
        return classify(run, baselinePace(all), medianDistanceKm(all));
    }

    private RunClassification classify(RunActivity run, double baselinePace, double medianDistanceKm) {
        RunSplits splits = splits(run, DEFAULT_SPLIT_METERS);
        boolean hasGps = !splits.splits().isEmpty();
        double distanceKm = PaceFormat.round(run.distanceMeters() / 1000d, 2);
        Double pace = run.avgPaceSecondsPerKm();
        double paceRatio = baselinePace > 0 && pace != null ? pace / baselinePace : 1d;
        Double stdDev = splits.paceStdDevSeconds();
        Double gapPace = splits.avgGradeAdjustedPaceSecondsPerKm();
        List<String> reasons = new ArrayList<>();

        double longThreshold = Math.max(10d, medianDistanceKm * 1.5d);
        boolean longRun = distanceKm >= longThreshold || run.durationSeconds() >= 90d * 60d;
        boolean standardDistance = nearestStandardDistance(distanceKm) != null;
        boolean race = standardDistance && pace != null && baselinePace > 0
                && paceRatio <= 0.92d && (stdDev == null || stdDev < 15d);
        double contrast = splitContrast(splits);
        // 间歇/变速跑应当有「多次快 + 多次慢」的交替，而不是某一段遇到红灯单独变慢。
        // 只看标准差或最快最慢差，会把长距离跑里的一个停顿误判成间歇。
        IntervalSignal interval = intervalSignal(splits, stdDev);
        boolean tempo = pace != null && baselinePace > 0 && paceRatio <= 0.95d
                && distanceKm >= 5d && (stdDev == null || stdDev < 20d);
        boolean recovery = pace != null && baselinePace > 0 && paceRatio >= 1.08d
                && distanceKm < medianDistanceKm * 1.2d;

        String type;
        String confidence;
        if (race) {
            type = "RACE";
            confidence = paceRatio <= 0.88d ? "HIGH" : "MEDIUM";
            reasons.add("距离 " + distanceKm + " 公里接近标准赛事距离");
            reasons.add("平均配速比个人中位快 " + Math.round((1d - paceRatio) * 100d) + "%");
        } else if (interval.isInterval()) {
            type = "INTERVAL";
            confidence = (stdDev != null && stdDev >= 30d) || contrast >= 90d ? "HIGH" : "MEDIUM";
            reasons.add("有 " + interval.fastSplits() + " 段明显快于中位配速、"
                    + interval.slowSplits() + " 段明显慢于中位配速，呈交替变速特征");
            if (stdDev != null) {
                reasons.add("分段配速标准差 " + stdDev + " 秒/公里");
            }
            if (contrast > 0) {
                reasons.add("最快与最慢分段相差 " + PaceFormat.round(contrast, 1) + " 秒/公里");
            }
        } else if (longRun) {
            type = "LONG_RUN";
            confidence = distanceKm >= Math.max(15d, medianDistanceKm * 1.8d)
                    || run.durationSeconds() >= 120d * 60d ? "HIGH" : "MEDIUM";
            if (distanceKm >= longThreshold) {
                reasons.add("距离 " + distanceKm + " 公里达到长距离阈值 "
                        + PaceFormat.round(longThreshold, 1) + " 公里");
            }
            if (run.durationSeconds() >= 90d * 60d) {
                reasons.add("时长 " + PaceFormat.duration(run.durationSeconds()) + " 达到 90 分钟");
            }
        } else if (tempo) {
            type = "TEMPO";
            confidence = paceRatio <= 0.90d && stdDev != null && stdDev < 10d ? "HIGH" : "MEDIUM";
            reasons.add("平均配速比个人中位快 " + Math.round((1d - paceRatio) * 100d) + "%，且分段波动较小");
        } else if (recovery) {
            type = "RECOVERY";
            confidence = "MEDIUM";
            reasons.add("平均配速比个人中位慢 " + Math.round((paceRatio - 1d) * 100d) + "%");
            reasons.add("距离 " + distanceKm + " 公里低于个人常规距离");
        } else {
            type = "EASY";
            confidence = hasGps ? "MEDIUM" : "LOW";
            reasons.add("配速接近个人中位，未触发高强度或长距离规则");
        }

        if (!hasGps) {
            confidence = "LOW";
            reasons.add("缺少 GPS 轨迹，仅按距离/时长/配速分类");
        }

        String typeText = typeText(type);
        String text = run.id() + " 判定为" + typeText + "（" + confidence + "）";
        return new RunClassification(run.id(), run.date(), type, typeText, confidence, reasons,
                distanceKm, pace, PaceFormat.pace(pace),
                baselinePace > 0 ? PaceFormat.round(baselinePace, 1) : null,
                PaceFormat.pace(baselinePace > 0 ? baselinePace : null),
                stdDev, gapPace, PaceFormat.pace(gapPace), text);
    }

    /**
     * 区间内的训练类型构成。逐条分类会按需解析轨迹，首次调用可能稍慢，之后命中轨迹缓存。
     */
    public TrainingMix trainingMix(DateRange range) {
        List<RunActivity> all = repository.dataset().activities();
        double baselinePace = baselinePace(all);
        double medianDistanceKm = medianDistanceKm(all);
        List<RunClassification> classified = runs(range).stream()
                .map(run -> classify(run, baselinePace, medianDistanceKm))
                .toList();

        Map<String, List<RunClassification>> byType = new LinkedHashMap<>();
        for (RunClassification result : classified) {
            byType.computeIfAbsent(result.type(), key -> new ArrayList<>()).add(result);
        }
        double totalDistance = classified.stream()
                .mapToDouble(RunClassification::distanceKm).sum();
        List<TrainingMix.TypeSummary> types = byType.entrySet().stream()
                .map(entry -> {
                    double distance = entry.getValue().stream()
                            .mapToDouble(RunClassification::distanceKm).sum();
                    return new TrainingMix.TypeSummary(
                            entry.getKey(),
                            typeText(entry.getKey()),
                            entry.getValue().size(),
                            PaceFormat.round(distance, 2),
                            totalDistance > 0 ? PaceFormat.round(distance / totalDistance * 100d, 1) : 0d);
                })
                .sorted(Comparator.comparingDouble(TrainingMix.TypeSummary::distanceKm).reversed())
                .toList();
        List<TrainingMix.RunTypeRow> rows = classified.stream()
                .map(result -> new TrainingMix.RunTypeRow(result.runId(), result.date(),
                        result.distanceKm(), result.paceText(), result.type(), result.typeText(),
                        result.confidence()))
                .toList();

        String text;
        if (types.isEmpty()) {
            text = "区间 " + range.text() + " 内没有可用于分类的记录";
        } else {
            List<String> parts = types.stream()
                    .map(type -> type.typeText() + " " + type.runCount() + " 次（" + type.percent() + "%）")
                    .toList();
            text = "区间 " + range.text() + " 共 " + classified.size() + " 次跑步："
                    + String.join("、", parts);
        }
        return new TrainingMix(range.from(), range.to(), classified.size(), types, rows, text);
    }

    private static double baselinePace(List<RunActivity> all) {
        List<Double> paces = all.stream()
                .map(RunActivity::avgPaceSecondsPerKm)
                .filter(Objects::nonNull)
                .filter(pace -> pace > 0)
                .sorted()
                .toList();
        return paces.isEmpty() ? 0d : median(paces);
    }

    private static double medianDistanceKm(List<RunActivity> all) {
        List<Double> distances = all.stream()
                .map(run -> run.distanceMeters() / 1000d)
                .filter(distance -> distance > 0)
                .sorted()
                .toList();
        return distances.isEmpty() ? 0d : median(distances);
    }

    private static double median(List<Double> sorted) {
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2d;
    }

    private static Double nearestStandardDistance(double distanceKm) {
        for (double standard : List.of(5d, 10d, 21.0975d, 42.195d)) {
            if (Math.abs(distanceKm - standard) / standard <= 0.03d) {
                return standard;
            }
        }
        return null;
    }

    /**
     * 判断是否为间歇/变速跑：只看整段（不含最后不满一段），要求至少 3 段，
     * 且同时有至少 2 段明显快、2 段明显慢于中位配速。这样能避免把长距离跑里
     * 遇到红灯或补水造成的单段变慢误判为变速训练。
     */
    private static IntervalSignal intervalSignal(RunSplits splits, Double stdDev) {
        List<Double> paces = splits.splits().stream()
                .filter(split -> !split.partial())
                .map(RunSplit::paceSecondsPerKm)
                .filter(Objects::nonNull)
                .filter(pace -> pace > 0)
                .sorted()
                .toList();
        if (paces.size() < 3 || stdDev == null || stdDev < 20d) {
            return new IntervalSignal(false, 0, 0);
        }
        double medianPace = median(paces);
        int fast = (int) paces.stream().filter(pace -> pace <= medianPace - 15d).count();
        int slow = (int) paces.stream().filter(pace -> pace >= medianPace + 15d).count();
        return new IntervalSignal(fast >= 2 && slow >= 2, fast, slow);
    }

    private record IntervalSignal(boolean isInterval, int fastSplits, int slowSplits) {
    }

    private static double splitContrast(RunSplits splits) {
        if (splits.fastest() == null || splits.slowest() == null) {
            return 0d;
        }
        Double fastest = splits.fastest().paceSecondsPerKm();
        Double slowest = splits.slowest().paceSecondsPerKm();
        return fastest == null || slowest == null ? 0d : Math.max(0d, slowest - fastest);
    }

    private static String typeText(String type) {
        return switch (type) {
            case "LONG_RUN" -> "长距离跑";
            case "TEMPO" -> "节奏跑";
            case "INTERVAL" -> "间歇/变速跑";
            case "RACE" -> "比赛/测试跑";
            case "RECOVERY" -> "恢复跑";
            default -> "轻松跑";
        };
    }

    private static double minettiCost(double grade) {
        double i = Math.clamp(grade, -0.45d, 0.45d);
        return 155.4d * i * i * i * i * i
                - 30.4d * i * i * i * i
                - 43.3d * i * i * i
                + 46.3d * i * i
                + 19.5d * i
                + MINETTI_FLAT_COST;
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
