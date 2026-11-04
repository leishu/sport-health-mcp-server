package org.example.sporthealth.tools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.BestEfforts;
import org.example.sporthealth.domain.Consistency;
import org.example.sporthealth.domain.DailyRunSummary;
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
import org.example.sporthealth.domain.RunSplits;
import org.example.sporthealth.domain.RunSummary;
import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.domain.StopPoint;
import org.example.sporthealth.domain.StopPoints;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.domain.TrainingMix;
import org.example.sporthealth.domain.TrainingTrend;
import org.example.sporthealth.domain.WeatherImpact;
import org.example.sporthealth.domain.WeatherSnapshot;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.repository.JdbcHealthDataRepository;
import org.example.sporthealth.repository.RunRows;
import org.example.sporthealth.repository.RunWeatherStore;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.example.sporthealth.support.WeatherImpactAnalyzer;
import org.example.sporthealth.weather.WeatherCodes;
import org.example.sporthealth.weather.WindDirections;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 暴露给 MCP 客户端的工具集合，数据全部来自本地导出的华为运动健康 TCX 文件。
 *
 * <p>通用约定：
 * <ul>
 *   <li>日期参数统一为 {@code yyyy-MM-dd}；不传时默认覆盖数据集自身的完整区间。</li>
 *   <li>所有工具方法统一返回 {@code Map<String, Object>}，字段由 MCP 层直接序列化成 JSON。
 *       返回体里的时间字段在工具层就格式化成 {@code yyyy-MM-dd HH:mm:ss}：本地时间按原值输出，
 *       {@code Instant}（UTC 语义）按 UTC 输出；只有日期的字段仍保持 {@code yyyy-MM-dd}。</li>
 *   <li>返回体通过 {@code status} 区分结果：{@code OK}、{@code NO_DATA}（区间内无记录或缺少所需数据）、
 *       {@code NO_GPS}（没有可用轨迹）、{@code NOT_FOUND}（id 不存在）、
 *       {@code AMBIGUOUS}（id 匹配到多条）。</li>
 *   <li>分页统一使用 {@code offset}/{@code limit}，列表与轨迹各有独立上限。</li>
 * </ul>
 */
@Component
@Slf4j
public class SportHealthTools {

    private static final int MAX_ROUTE_LIMIT = 5000;
    private static final double DEFAULT_SPLIT_KM = 1d;
    private static final double DEFAULT_ELEVATION_BUCKET_METERS = 100d;
    private static final double DEFAULT_ELEVATION_SMOOTHING_METERS = 1d;
    /** 与区间中位配速相差超过这个百分比即视为离群。 */
    private static final double PACE_OUTLIER_PERCENT = 25d;
    /** 记录数少于这个值时中位数不具代表性，不做配速离群判定。 */
    private static final int MIN_RUNS_FOR_PACE_OUTLIER = 3;
    /** 返回体里的本地时间统一成这个格式，避免默认的 ISO-8601（带 T）。 */
    private static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    /** {@code Instant} 本身是 UTC 语义，按 UTC 格式化，不伪装成本地时间。 */
    private static final DateTimeFormatter UTC_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final RunAnalyticsService analytics;
    private final HealthDataRepository repository;
    private final HealthDataProperties properties;
    /** 可选：非 JDBC 仓储（单测里的内存实现）没有 {@code run_weather} 表，此时为 null。 */
    private final RunWeatherStore weatherStore;

    public SportHealthTools(RunAnalyticsService analytics,
                            HealthDataRepository repository,
                            HealthDataProperties properties,
                            RunWeatherStore weatherStore) {
        this.analytics = analytics;
        this.repository = repository;
        this.properties = properties;
        this.weatherStore = weatherStore;
    }

    /** 区间内的记录列表，新的在前，支持过滤、排序与分页。 */
    @McpTool(name = "list_runs",
            description = "按日期区间列出跑步记录，返回每次跑步的距离、时长、配速、爬升与轨迹点数。"
                    + "支持按运动类型、距离、配速、星期过滤，并按距离/时长/配速/爬升排序。"
                    + "不传日期时默认返回数据集中全部记录。用于回答“我最近跑了哪些”“有哪些长距离跑”。")
    public Map<String, Object> listRuns(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "运动类型，例如 户外跑步；不传表示不过滤", required = false) String sport,
            @McpToolParam(description = "最小距离（公里，含）", required = false) Double minDistanceKm,
            @McpToolParam(description = "最大距离（公里，含）", required = false) Double maxDistanceKm,
            @McpToolParam(description = "配速数值下限（秒/公里），数值越小越快；例如 330 表示不返回快于 5'30\" 的记录", required = false) Double minPaceSecondsPerKm,
            @McpToolParam(description = "配速数值上限（秒/公里），例如 400 表示不返回慢于 6'40\" 的记录", required = false) Double maxPaceSecondsPerKm,
            @McpToolParam(description = "星期几，1=周一 … 7=周日", required = false) Integer weekday,
            @McpToolParam(description = "排序：date（默认，新的在前）、distance、duration、pace（快的在前）、climb", required = false) String sortBy,
            @McpToolParam(description = "分页偏移，默认 0", required = false) Integer offset,
            @McpToolParam(description = "返回条数，默认 20，最多 200", required = false) Integer limit) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        RunFilter filter = new RunFilter(sport, minDistanceKm, maxDistanceKm,
                minPaceSecondsPerKm, maxPaceSecondsPerKm, weekday, sortBy);
        Page<RunActivity> page = analytics.list(range, filter, orZero(offset), pageSize(limit));
        if (page.total() == 0) {
            return noData(range, filter.isFiltered() ? "过滤条件：" + filter.describe() : null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("sortBy", filter.sortBy());
        if (filter.isFiltered()) {
            result.put("filter", filter.describe());
        }
        result.put("total", page.total());
        result.put("offset", page.offset());
        result.put("limit", page.limit());
        result.put("hasMore", page.hasMore());
        result.put("runs", page.items().stream().map(this::toRunRow).toList());
        return result;
    }

    /** 单次详情；{@code includeTrack=true} 时才按需加载轨迹并分页返回。 */
    @McpTool(name = "get_run_detail",
            description = "查看某一次跑步的完整详情，包含起止时间、总时长、距离、平均配速、平均速度、累计爬升与下降、"
                    + "海拔范围、起终点坐标，以及这次跑步起跑与结束时刻的天气（气温、体感温度、湿度、降水量、"
                    + "风向风速、天气现象）；可选按页返回 GPS 轨迹点。runId 先用 list_runs 获取。"
                    + "用于回答“这次跑步的天气怎么样”“那天多少度”。")
    public Map<String, Object> getRunDetail(
            @McpToolParam(description = "跑步记录 id，例如 20261025户外跑步") String runId,
            @McpToolParam(description = "是否返回轨迹点，默认 false", required = false) Boolean includeTrack,
            @McpToolParam(description = "轨迹分页偏移，默认 0", required = false) Integer trackOffset,
            @McpToolParam(description = "轨迹返回条数，默认 200，最多 5000", required = false) Integer trackLimit) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        boolean withTrack = Boolean.TRUE.equals(includeTrack);
        RunDetail detail = analytics.detail(run, orZero(trackOffset),
                withTrack ? routePageSize(trackLimit) : 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("activity", toActivityDetail(run));
        result.put("paceText", detail.paceText());
        result.put("speedText", detail.speedText());
        Object weather = weatherDetail(run.id());
        if (weather != null) {
            result.put("weather", weather);
        }
        if (detail.route() != null) {
            result.put("route", toRoutePage(detail.route()));
        }
        return result;
    }

    /** 只取轨迹点，适合分析路线或绘制地图；轨迹按需解析后缓存。 */
    @McpTool(name = "get_run_route",
            description = "分页读取某次跑步的 GPS 轨迹点（时间、纬度、经度、海拔），用于分析路线、配速变化或绘制轨迹。"
                    + "支持 format=geojson 直接返回 LineString，或用 maxPoints 降采样控制返回体积，避免一次拉取上千个点。")
    public Map<String, Object> getRunRoute(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "分页偏移，默认 0；使用 maxPoints 时忽略", required = false) Integer offset,
            @McpToolParam(description = "返回条数，默认 200，最多 5000；使用 maxPoints 时忽略", required = false) Integer limit,
            @McpToolParam(description = "返回格式：points（默认）或 geojson", required = false) String format,
            @McpToolParam(description = "降采样到最多多少个点；设置后忽略 offset/limit", required = false) Integer maxPoints) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        List<TrackPoint> route = repository.route(run.id());
        if (route.isEmpty()) {
            return noGps(run.id(), "没有可用的轨迹点");
        }

        if ("geojson".equalsIgnoreCase(format)) {
            List<TrackPoint> sampled = downsample(route, maxPoints);
            List<List<Double>> coordinates = sampled.stream()
                    .filter(TrackPoint::hasPosition)
                    .map(point -> {
                        List<Double> coordinate = new ArrayList<>(3);
                        coordinate.add(point.longitude());
                        coordinate.add(point.latitude());
                        if (point.altitudeMeters() != null) {
                            coordinate.add(PaceFormat.round(point.altitudeMeters(), 2));
                        }
                        return coordinate;
                    })
                    .toList();
            Map<String, Object> geometry = new LinkedHashMap<>();
            geometry.put("type", "LineString");
            geometry.put("coordinates", coordinates);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("runId", run.id());
            result.put("date", run.date());
            result.put("pointCount", coordinates.size());
            result.put("geometry", geometry);
            return result;
        }

        Page<TrackPoint> page;
        if (maxPoints != null && maxPoints > 0) {
            List<TrackPoint> sampled = downsample(route, maxPoints);
            page = Page.of(sampled, 0, sampled.size());
        } else {
            page = Page.of(route, orZero(offset), routePageSize(limit));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.putAll(toRoutePage(page));
        return result;
    }

    /** 区间汇总；整体配速由总距离与总时长计算。 */
    @McpTool(name = "get_running_summary",
            description = "统计一个日期区间的跑步总量：次数、总公里数、总时长、总爬升、平均每次距离与配速，"
                    + "以及最快配速与最长距离对应的记录。不传日期时统计全部数据。"
                    + "用于回答“这个月跑了多少”“最近一周跑量多少”。")
    public Map<String, Object> getRunningSummary(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd", required = false) String endDate) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        RunSummary summary = analytics.summary(range);
        if (summary.runCount() == 0) {
            return noData(range, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("summary", summary);
        result.put("text", summaryText(summary));
        return result;
    }

    /**
     * 单次跑步的天气：把 {@code run_weather} 的起跑/结束两个时刻摊平成可直接阅读的字段。
     *
     * <p>{@code weatherStore} 是可选的：非 JDBC 仓储（单测里的内存实现）没有天气表，
     * 构造时传 {@code null}，此时整个字段不出现。
     *
     * <p>没有对应行说明还没回填过，返回 {@code status = MISSING} 并提示同步一次；
     * 查询异常只降级为带 {@code error} 的块，不把天气问题变成整个工具的失败。
     */
    private Object weatherDetail(String runId) {
        if (weatherStore == null) {
            return null;
        }
        try {
            return weatherStore.find(runId).<Object>map(this::weatherBlock).orElseGet(() -> {
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("status", "MISSING");
                missing.put("message", "这条记录还没有回填天气，调用 reload_data 同步一次后即可补齐");
                return missing;
            });
        } catch (RuntimeException e) {
            log.warn("读取记录 {} 的天气失败", runId, e);
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> weatherBlock(RunWeather weather) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("status", weather.status().name());
        if (weather.errorMessage() != null) {
            block.put("errorMessage", weather.errorMessage());
        }
        block.put("provider", weather.provider());
        block.put("fetchedAt", formatDateTime(weather.fetchedAt()));
        block.put("start", weatherPoint(weather.start()));
        block.put("end", weatherPoint(weather.end()));
        return block;
    }

    /** 一个时刻的天气；{@code null} 快照输出 {@code null}，不能用空了字段的对象冒充「0 度」 */
    private Map<String, Object> weatherPoint(WeatherSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("observedAt", formatDateTime(snapshot.observedAtUtc()));
        point.put("temperatureC", round(snapshot.temperatureC(), 1));
        point.put("apparentTemperatureC", round(snapshot.apparentTemperatureC(), 1));
        point.put("humidityPercent", round(snapshot.humidityPercent(), 1));
        // 降水量常在 0.1～2 mm 之间（前一小时累计），只留 1 位小数会把毛毛雨抹成 0
        point.put("precipitationMm", round(snapshot.precipitationMm(), 2));
        point.put("windSpeedKmh", round(snapshot.windSpeedKmh(), 1));
        point.put("windDirectionDegrees", snapshot.windDirectionDegrees());
        point.put("windDirection", WindDirections.text(snapshot.windDirectionDegrees()));
        point.put("weatherCode", snapshot.weatherCode());
        point.put("condition", WeatherCodes.describe(snapshot.weatherCode()));
        return point;
    }

    private static Double round(Double value, int scale) {
        return value == null ? null : PaceFormat.round(value, scale);
    }

    /** 本地时间按 {@code yyyy-MM-dd HH:mm:ss} 输出；null 保持 null，不写成空字符串。 */
    private static String formatDateTime(LocalDateTime value) {
        return value == null ? null : LOCAL_TIME_FORMAT.format(value);
    }

    /** UTC 时刻同样用这个格式，但不带 {@code Z}，保留「这是 UTC」的字段语义。 */
    private static String formatDateTime(Instant value) {
        return value == null ? null : UTC_TIME_FORMAT.format(value);
    }

    /** 仓储里直接取出的 ISO 时间文本（如天气统计的 lastFetchedAt）也要归一化。 */
    private static String formatDateTimeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return formatDateTime(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    /** 按天 / 周 / 月聚合，只返回有记录的周期。 */
    @McpTool(name = "get_daily_running",
            description = "按天、周或月拆分跑步数据：每个周期跑了多少次、距离、时长、配速与爬升。"
                    + "groupBy=day（默认）看训练频率，week/month 看周跑量或月跑量。只返回有记录的周期。")
    public Map<String, Object> getDailyRunning(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd", required = false) String endDate,
            @McpToolParam(description = "分组粒度：day（默认）、week、month", required = false) String groupBy) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        String mode = normalizeGroup(groupBy);
        List<PeriodSummary> groups = analytics.grouped(range, mode);
        if (groups.isEmpty()) {
            return noData(range, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("groupBy", mode);
        result.put("groupCount", groups.size());
        result.put("groups", groups);
        if ("day".equals(mode)) {
            List<DailyRunSummary> days = analytics.daily(range);
            result.put("days", days);
            result.put("recordedDays", days.size());
        }
        return result;
    }

    /** 个人最佳，可按类别只取一项。 */
    @McpTool(name = "get_personal_bests",
            description = "查询区间内的个人最佳：最长距离、最快配速、最长时长、最大爬升各对应一次跑步记录，"
                    + "以及区间内最快的 1 公里 / 5 公里（基于 GPS 轨迹，逐条解析轨迹）。"
                    + "category 可只取其中一项。用于回答“我最好成绩是多少”“我 1 公里最快多少”。")
    public Map<String, Object> getPersonalBests(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd", required = false) String endDate,
            @McpToolParam(description = "类别：all（默认）、distance、pace、duration、climb、best1k、best5k",
                    required = false) String category) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        PersonalBests bests = analytics.bests(range);
        if (bests.runCount() == 0) {
            return noData(range, null);
        }
        String key = normalizeCategory(category);
        Map<String, Object> bestRows = new LinkedHashMap<>();
        if ("all".equals(key) || "distance".equals(key)) {
            bestRows.put("longestDistance", toRunRow(bests.longestDistance()));
        }
        if ("all".equals(key) || "pace".equals(key)) {
            bestRows.put("fastestPace", toRunRow(bests.fastestPace()));
        }
        if ("all".equals(key) || "duration".equals(key)) {
            bestRows.put("longestDuration", toRunRow(bests.longestDuration()));
        }
        if ("all".equals(key) || "climb".equals(key)) {
            bestRows.put("mostClimb", toRunRow(bests.mostClimb()));
        }

        boolean wantsEfforts = "all".equals(key) || "best1k".equals(key) || "best5k".equals(key);
        boolean hasEffort = false;
        if (wantsEfforts) {
            List<Double> targets = new ArrayList<>(2);
            if (!"best5k".equals(key)) {
                targets.add(1000d);
            }
            if (!"best1k".equals(key)) {
                targets.add(5000d);
            }
            Map<Double, PersonalBestEffort> efforts = analytics.effortBests(range, targets).stream()
                    .collect(Collectors.toMap(PersonalBestEffort::targetMeters, effort -> effort));
            PersonalBestEffort best1k = efforts.get(1000d);
            PersonalBestEffort best5k = efforts.get(5000d);
            if (best1k != null) {
                bestRows.put("best1k", best1k);
                hasEffort = true;
            }
            if (best5k != null) {
                bestRows.put("best5k", best5k);
                hasEffort = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("category", key);
        result.put("bests", bestRows);
        if (wantsEfforts && !hasEffort) {
            result.put("message", "区间内没有可用 GPS 轨迹，或单次距离不足 1 公里，无法计算 1 公里 / 5 公里最佳");
        }
        return result;
    }

    /**
     * 比赛成绩预测：用区间内的最佳努力做 Riegel 外推。
     * 目标距离本身有实测成绩时直接返回实测值，并标记 {@code source=ACTUAL}。
     */
    @McpTool(name = "predict_race_time",
            description = "根据区间内的最佳努力预测比赛成绩（Riegel 公式）。不传 distanceKm 时输出 5K、10K、"
                    + "半马、全马四档；传 distanceKm 时只预测该距离。每条预测都带参考距离、参考记录与置信度，"
                    + "参考成绩越接近目标距离越可靠。用于回答“我现在的水平全马能跑多少”“目标 10K 该按什么配速跑”。")
    public Map<String, Object> predictRaceTime(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "目标距离（公里）；不传则输出 5K/10K/半马/全马", required = false) Double distanceKm) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        RacePrediction prediction = analytics.predictRace(range, distanceKm);
        if (prediction.predictions().isEmpty()) {
            return noData(range, prediction.text());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("method", prediction.method());
        result.put("exponent", prediction.exponent());
        result.put("references", prediction.references());
        result.put("predictions", prediction.predictions());
        result.put("text", prediction.text());
        return result;
    }

    /** 两次对比，结论直接回读给用户；任一 id 多义时返回 AMBIGUOUS。 */
    @McpTool(name = "compare_runs",
            description = "对比两次跑步的距离、时长与配速差异，并给出一句话结论（谁更快、快多少）。"
                    + "includeSplits=true 时附带每公里分段，便于看配速分布。两次记录 id 都用 list_runs 获取。")
    public Map<String, Object> compareRuns(
            @McpToolParam(description = "基准跑步记录 id") String baseRunId,
            @McpToolParam(description = "对比跑步记录 id") String otherRunId,
            @McpToolParam(description = "是否附带两次的每公里分段，默认 false", required = false) Boolean includeSplits) {
        RunLookup baseLookup = analytics.lookup(baseRunId);
        if (baseLookup.isAmbiguous()) {
            return ambiguous(baseRunId, baseLookup);
        }
        if (!baseLookup.isFound()) {
            return notFound(baseRunId);
        }
        RunLookup otherLookup = analytics.lookup(otherRunId);
        if (otherLookup.isAmbiguous()) {
            return ambiguous(otherRunId, otherLookup);
        }
        if (!otherLookup.isFound()) {
            return notFound(otherRunId);
        }
        RunActivity base = baseLookup.run();
        RunActivity other = otherLookup.run();
        RunComparison comparison = analytics.compare(base, other);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("verdict", comparison.verdict());
        result.put("paceDiffSecondsPerKm", comparison.paceDiffSecondsPerKm());
        result.put("distanceDiffKm", comparison.distanceDiffKm());
        result.put("durationDiffMinutes", comparison.durationDiffMinutes());
        result.put("base", toRunRow(base));
        result.put("other", toRunRow(other));
        if (Boolean.TRUE.equals(includeSplits)) {
            Map<String, Object> splits = new LinkedHashMap<>();
            splits.put("base", analytics.splits(base, 1000d).splits());
            splits.put("other", analytics.splits(other, 1000d).splits());
            result.put("splits", splits);
        }
        return result;
    }

    /** 每公里（或自定义距离）分段配速。 */
    @McpTool(name = "get_run_splits",
            description = "把一次跑步按固定距离（默认 1 公里）切分，返回每段距离、用时、配速、爬升、净海拔变化、"
                    + "平均坡度与坡度修正配速（GAP），以及最快/最慢分段、前后半程配速、是否负分段、配速稳定性。"
                    + "GAP 把上坡折算成等效平路配速，便于山路和平路横向比较。"
                    + "用于回答“每公里配速多少”“有没有跑崩”“后半程更快吗”“山路实际强度多大”。")
    public Map<String, Object> getRunSplits(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "分段距离（公里），默认 1", required = false) Double splitKm) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        double splitMeters = splitKm != null && splitKm > 0 ? splitKm * 1000d : DEFAULT_SPLIT_KM * 1000d;
        RunSplits splits = analytics.splits(run, splitMeters);
        if (splits.splits().isEmpty()) {
            return noGps(run.id(), "没有可用的 GPS 轨迹，无法计算分段配速");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.putAll(splitsMap(splits));
        return result;
    }

    /** 一次跑步内 1K / 5K 等目标距离的最佳用时。 */
    @McpTool(name = "get_best_efforts",
            description = "计算一次跑步内指定目标距离（默认 1 公里、5 公里）的最佳用时与折算配速，"
                    + "例如“这 10 公里里最快的 5 公里是多少”。超过本次轨迹距离的目标会列在 unavailableTargetsMeters。")
    public Map<String, Object> getBestEfforts(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "目标距离列表（公里），默认 [1, 5]；例如 [1,5,10]", required = false) List<Double> distancesKm) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        List<Double> targets = distancesKm == null || distancesKm.isEmpty()
                ? List.of(1000d, 5000d)
                : distancesKm.stream()
                        .filter(Objects::nonNull)
                        .filter(km -> km > 0)
                        .map(km -> km * 1000d)
                        .toList();
        BestEfforts efforts = analytics.bestEfforts(run, targets);
        if (efforts.gpsDistanceKm() <= 0) {
            return noGps(run.id(), "没有可用的 GPS 轨迹，无法计算目标距离的最佳用时");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.put("gpsDistanceKm", efforts.gpsDistanceKm());
        result.put("efforts", efforts.efforts());
        result.put("unavailableTargetsMeters", efforts.unavailableTargets());
        if (efforts.efforts().isEmpty()) {
            result.put("message", "所有目标距离都超过本次轨迹距离 " + efforts.gpsDistanceKm()
                    + " 公里，可改小 distances 或先用 list_runs 确认距离");
        }
        return result;
    }

    /** 周 / 月训练趋势。 */
    @McpTool(name = "get_training_trend",
            description = "按周或按月生成连续周期趋势：每个周期的次数、距离、时长、平均配速、爬升，"
                    + "以及与上一周期的跑量百分比变化和配速差。用于回答“这周比上周多跑了多少”“最近配速在变快吗”。")
    public Map<String, Object> getTrainingTrend(
            @McpToolParam(description = "周期粒度：week（默认）或 month", required = false) String period,
            @McpToolParam(description = "返回多少个周期，默认 4，最多 52", required = false) Integer count,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最后一天所属周期", required = false) String endDate) {
        DateRange anchorRange = analytics.resolveRange(null, endDate);
        TrainingTrend trend = analytics.trend(period, count == null ? 4 : count, anchorRange.to());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("period", trend.period());
        result.put("count", trend.count());
        result.put("periods", trend.periods());
        result.put("text", trend.text());
        return result;
    }

    /**
     * 训练负荷与 ACWR：回答“最近加量是不是太快”“有没有伤病风险”。
     */
    @McpTool(name = "get_training_load",
            description = "计算训练负荷与伤病风险：最近 7 天急性负荷、28 天慢性负荷、ACWR（急性/慢性周均）、"
                    + "周跑量增幅、连续训练天数、休息日与训练单调性，并给出调整建议。"
                    + "metric 可选 distance（默认，公里）或 duration（分钟）。数据覆盖不足时会明确标注置信度，"
                    + "不会用几天的数据给出确定结论。")
    public Map<String, Object> getTrainingLoad(
            @McpToolParam(description = "评估截止日期，格式 yyyy-MM-dd，默认取数据最后一天", required = false) String endDate,
            @McpToolParam(description = "负荷口径：distance（默认，公里）或 duration（分钟）", required = false) String metric) {
        DateRange range = analytics.resolveRange(null, endDate);
        TrainingLoad load = analytics.trainingLoad(range.to(), metric);
        if (load.chronic().runCount() == 0) {
            return noData(new DateRange(load.anchorDate().minusDays(27), load.anchorDate()), null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("anchorDate", load.anchorDate());
        result.put("metric", load.metric());
        result.put("acute", load.acute());
        result.put("chronic", load.chronic());
        result.put("chronicWeeklyAverage", load.chronicWeeklyAverage());
        result.put("acwr", load.acwr());
        result.put("chronicCoverageDays", load.chronicCoverageDays());
        result.put("dataSufficient", load.dataSufficient());
        result.put("confidence", load.confidence());
        result.put("riskLevel", load.riskLevel());
        result.put("weeklyRampPercent", load.weeklyRampPercent());
        result.put("consecutiveTrainingDays", load.consecutiveTrainingDays());
        result.put("restDaysLast7", load.restDaysLast7());
        result.put("monotony", load.monotony());
        result.put("strain", load.strain());
        result.put("recommendation", load.recommendation());
        result.put("text", load.text());
        return result;
    }

    /**
     * 单次跑步的训练类型识别。
     */
    @McpTool(name = "classify_run",
            description = "识别一次跑步的训练类型：轻松跑、长距离跑、节奏跑、间歇/变速跑、比赛/测试跑或恢复跑。"
                    + "没有心率数据，分类基于距离、时长、配速相对个人中位数以及分段配速波动，"
                    + "返回判定依据与置信度。用于回答“这次算轻松跑还是节奏跑”。")
    public Map<String, Object> classifyRun(
            @McpToolParam(description = "跑步记录 id，例如 20261025户外跑步") String runId) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunClassification classification = analytics.classify(lookup.run());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", classification.runId());
        result.put("date", classification.date());
        result.put("type", classification.type());
        result.put("typeText", classification.typeText());
        result.put("confidence", classification.confidence());
        result.put("reasons", classification.reasons());
        result.put("distanceKm", classification.distanceKm());
        result.put("paceSecondsPerKm", classification.paceSecondsPerKm());
        result.put("paceText", classification.paceText());
        result.put("baselinePaceSecondsPerKm", classification.baselinePaceSecondsPerKm());
        result.put("baselinePaceText", classification.baselinePaceText());
        result.put("paceStdDevSeconds", classification.paceStdDevSeconds());
        result.put("gradeAdjustedPaceSecondsPerKm", classification.gradeAdjustedPaceSecondsPerKm());
        result.put("gradeAdjustedPaceText", classification.gradeAdjustedPaceText());
        result.put("text", classification.text());
        return result;
    }

    /**
     * 区间内的训练类型构成：看训练结构是否均衡，而不是只看总跑量。
     */
    @McpTool(name = "get_training_mix",
            description = "统计一个日期区间内的训练类型构成：轻松跑、长距离、节奏跑、间歇/变速、比赛/恢复各占多少次、"
                    + "多少公里与百分比，并逐条列出分类结果。逐条分类会按需解析轨迹，首次调用稍慢。"
                    + "用于回答“我的训练是不是全是轻松跑”“长距离占比够不够”。")
    public Map<String, Object> getTrainingMix(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        TrainingMix mix = analytics.trainingMix(range);
        if (mix.totalRuns() == 0) {
            return noData(range, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("totalRuns", mix.totalRuns());
        result.put("types", mix.types());
        result.put("runs", mix.runs());
        result.put("text", mix.text());
        return result;
    }

    /** 连续打卡与训练频率。 */
    @McpTool(name = "get_consistency",
            description = "统计区间内的打卡连续性：有记录天数、占比、当前连续天数、最长连续天数、平均间隔，"
                    + "并返回每天的距离用于日历热力图。当前连续以最后一次记录为锚点，历史数据不会因为“今天没跑”变成 0。")
    public Map<String, Object> getConsistency(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd", required = false) String endDate) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        Consistency consistency = analytics.consistency(range);
        if (consistency.activeDays() == 0) {
            return noData(range, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("activeDays", consistency.activeDays());
        result.put("totalDays", consistency.totalDays());
        result.put("activeRatePercent", consistency.activeRatePercent());
        result.put("currentStreak", consistency.currentStreak());
        result.put("longestStreak", consistency.longestStreak());
        result.put("avgGapDays", consistency.avgGapDays());
        result.put("lastActiveDate", consistency.lastActiveDate());
        result.put("days", consistency.days());
        result.put("text", consistency.text());
        return result;
    }

    /** 海拔剖面。 */
    @McpTool(name = "get_elevation_profile",
            description = "按距离分桶返回海拔剖面：每段的平均/最低/最高海拔、累计爬升与下降、平均坡度，"
                    + "并汇总全程爬升。smoothingMeters 用于过滤 GPS 高程抖动。用于回答“爬升集中在哪”“坡陡不陡”。")
    public Map<String, Object> getElevationProfile(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "每个桶的距离（米），默认 100，最小 10", required = false) Double bucketMeters,
            @McpToolParam(description = "爬升平滑阈值（米），默认 1，0 表示不平滑", required = false) Double smoothingMeters) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        double bucket = bucketMeters != null && bucketMeters > 0
                ? bucketMeters : DEFAULT_ELEVATION_BUCKET_METERS;
        double smoothing = smoothingMeters != null && smoothingMeters >= 0
                ? smoothingMeters : DEFAULT_ELEVATION_SMOOTHING_METERS;
        ElevationProfile profile = analytics.elevationProfile(run, bucket, smoothing);
        if (profile.minAltitudeMeters() == null) {
            return noData(run.id(), "本次轨迹没有海拔数据");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", profile.runId());
        result.put("date", profile.date());
        result.put("bucketMeters", profile.bucketMeters());
        result.put("smoothingMeters", profile.smoothingMeters());
        result.put("gpsDistanceKm", profile.gpsDistanceKm());
        result.put("totalGainMeters", profile.totalGainMeters());
        result.put("totalLossMeters", profile.totalLossMeters());
        result.put("minAltitudeMeters", profile.minAltitudeMeters());
        result.put("maxAltitudeMeters", profile.maxAltitudeMeters());
        result.put("buckets", profile.buckets());
        return result;
    }

    /** 中途停顿检测。 */
    @McpTool(name = "get_stop_points",
            description = "检测跑步过程中的停顿（等红灯、补水、休息）：返回每次停顿的开始/结束时间、时长与位置，"
                    + "以及停顿总时长。判定条件是相邻轨迹点间隔达到阈值且位移很小。")
    public Map<String, Object> getStopPoints(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "最短停顿秒数，默认 30", required = false) Double minStopSeconds,
            @McpToolParam(description = "停顿期间允许的最大位移（米），默认 30", required = false) Double maxDisplacementMeters) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        double minSeconds = minStopSeconds != null && minStopSeconds >= 1
                ? minStopSeconds : 30d;
        double maxDisplacement = maxDisplacementMeters != null && maxDisplacementMeters >= 0
                ? maxDisplacementMeters : 30d;
        StopPoints stops = analytics.stopPoints(run, minSeconds, maxDisplacement);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.put("minStopSeconds", minSeconds);
        result.put("maxDisplacementMeters", maxDisplacement);
        result.put("stopCount", stops.stopCount());
        result.put("totalStopSeconds", stops.totalStopSeconds());
        result.put("totalStopText", stops.totalStopText());
        result.put("stops", stops.stops().stream().map(this::toStopPoint).toList());
        return result;
    }

    /**
     * 天气影响分析：把已有 {@code run_weather} 数据按温度/湿度/风速/降水分组，比较配速差异。
     * 不触发天气抓取，只读已经回填的行；没有天气数据时提示先同步。
     */
    @McpTool(name = "get_weather_impact",
            description = "分析天气对配速的影响：按起跑气温、湿度、风速、有无降水分组，返回每组的次数、"
                    + "加权平均配速、相对整体基线的快慢秒数，以及气温与配速的相关系数。"
                    + "只使用已经回填的天气数据，不会联网；没有天气时先调用 reload_data。"
                    + "用于回答“我热天是不是掉速”“下雨天跑得慢多少”。")
    public Map<String, Object> getWeatherImpact(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "只看至少这么长距离的跑步（公里），默认 0", required = false) Double minDistanceKm) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        if (weatherStore == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NO_DATA");
            result.put("message", "当前仓储没有天气表，无法分析天气影响；请先调用 reload_data 同步数据。");
            result.put("range", range.text());
            return result;
        }
        double minDistance = minDistanceKm != null && minDistanceKm > 0 ? minDistanceKm : 0d;
        WeatherImpact impact;
        try {
            impact = WeatherImpactAnalyzer.analyze(range, analytics.runs(range),
                    weatherStore.findAll(), minDistance);
        } catch (RuntimeException e) {
            log.warn("天气影响分析失败", e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NO_DATA");
            result.put("message", "读取天气数据失败：" + e.getMessage());
            result.put("range", range.text());
            return result;
        }
        if (impact.sampleSize() == 0) {
            return noData(range, impact.text());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("totalRuns", impact.totalRuns());
        result.put("sampleSize", impact.sampleSize());
        result.put("baselinePaceSecondsPerKm", impact.baselinePaceSecondsPerKm());
        result.put("baselinePaceText", impact.baselinePaceText());
        result.put("temperatureBuckets", impact.temperatureBuckets());
        result.put("humidityBuckets", impact.humidityBuckets());
        result.put("windBuckets", impact.windBuckets());
        result.put("dry", impact.dry());
        result.put("wet", impact.wet());
        result.put("temperaturePaceCorrelation", impact.temperaturePaceCorrelation());
        result.put("text", impact.text());
        return result;
    }

    /** 数据源自检入口：文件、条数、日期范围、来源、RouteType、解析告警与数据质量。 */
    @McpTool(name = "describe_data_source",
            description = "查看当前数据源状态：数据目录、已加载的 TCX 文件、跑步记录总数、覆盖日期范围、"
                    + "累计距离、解析告警分类以及数据质量检查（0 距离、缺配速/坐标/海拔、重复 Activity Id、"
                    + "配速离群、重复时间戳）。数据看起来不对时先调用它排查；重复时间戳需要解析全部轨迹，首次调用稍慢。")
    public Map<String, Object> describeDataSource() {
        RunDataset dataset = repository.dataset();
        List<RunActivity> activities = dataset.activities();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("dataDir", dataset.dataDir());
        result.put("zoneId", properties.zoneId());
        result.put("loadedAt", formatDateTime(dataset.loadedAt()));
        result.put("fileCount", dataset.sourceFiles().size());
        result.put("runCount", dataset.runCount());
        result.put("files", dataset.sourceFiles());
        result.put("warnings", dataset.warnings());
        result.put("storage", storageInfo());

        if (!activities.isEmpty()) {
            result.put("firstDate", activities.stream().map(RunActivity::date).min(java.util.Comparator.naturalOrder()).orElse(null));
            result.put("lastDate", activities.stream().map(RunActivity::date).max(java.util.Comparator.naturalOrder()).orElse(null));
            result.put("totalDistanceKm", PaceFormat.round(
                    activities.stream().mapToDouble(RunActivity::distanceMeters).sum() / 1000d, 2));
            result.put("sports", activities.stream().map(RunActivity::sport).distinct().toList());
            result.put("creators", activities.stream().map(RunActivity::creator)
                    .filter(creator -> creator != null && !creator.isBlank()).distinct().toList());
            result.put("routeTypes", activities.stream().map(RunActivity::routeTypeText)
                    .filter(Objects::nonNull).distinct().toList());
        }
        result.put("quality", quality(dataset));
        return result;
    }

    /** 重新扫描并增量同步，旧轨迹缓存随之丢弃。 */
    @McpTool(name = "reload_data",
            description = "重新扫描数据目录，把新增或内容变化的 TCX 文件增量同步进 SQLite，无需重启服务。"
                    + "未变化的文件按 size/mtime/sha256 跳过，不会重复解析；源文件已删除的历史记录会保留。")
    public Map<String, Object> reloadData() {
        repository.reload();
        RunDataset dataset = repository.dataset();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "已同步 " + dataset.runCount() + " 条跑步记录，来源文件 "
                + dataset.sourceFiles().size() + " 个");
        result.put("runCount", dataset.runCount());
        result.put("warnings", dataset.warnings());
        result.put("storage", storageInfo());
        return result;
    }

    /**
     * 持久化层状态：库里现有记录量、本次同步的增量统计。
     *
     * <p>只在仓储实现为 JDBC 版时给出明细，其它实现（含测试中的内存实现）返回 {@code null}，
     * 保证工具层不依赖具体仓储类型。
     */
    private Map<String, Object> storageInfo() {
        if (!(repository instanceof JdbcHealthDataRepository jdbcRepository)) {
            return null;
        }
        RunRows.SyncResult sync = jdbcRepository.lastSync();
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", "sqlite");
        storage.put("dbPath", properties.dbPath());
        storage.put("scannedFiles", sync.scanned());
        storage.put("importedFiles", sync.imported());
        storage.put("unchangedFiles", sync.unchanged());
        storage.put("syncedAt", formatDateTime(sync.syncedAt()));
        storage.put("missingSourceFiles", sync.missingSourceFiles());
        Map<String, Object> weather = weatherInfo();
        if (weather != null) {
            storage.put("weather", weather);
        }
        return storage;
    }

    /**
     * 天气回填的覆盖率。
     *
     * <p>只作为 {@code describe_data_source} 的一个字段暴露，不单独提供工具：
     * 天气是跑步记录的补充属性，坏了要能看见，但不需要独立入口。
     * 端点不可达时回填会静默降级为 FAILED，这里的数字是唯一的发现途径。
     *
     * <p>{@code weatherStore} 是可选的：非 JDBC 仓储（单测里的内存实现）没有天气表，
     * 构造时传 {@code null}，此时整个字段不出现，而不是输出一堆 0。
     */
    private Map<String, Object> weatherInfo() {
        if (weatherStore == null) {
            return null;
        }
        try {
            RunWeatherStore.Stats stats = weatherStore.stats();
            Map<String, Object> weather = new LinkedHashMap<>();
            weather.put("totalRuns", stats.totalRuns());
            weather.put("ok", stats.ok());
            weather.put("unavailable", stats.unavailable());
            weather.put("failed", stats.failed());
            weather.put("missing", stats.missing());
            weather.put("lastFetchedAt", formatDateTimeText(stats.lastFetchedAt()));
            weather.put("coveragePercent", stats.totalRuns() == 0
                    ? null
                    : PaceFormat.round(stats.ok() * 100d / stats.totalRuns(), 1));
            return weather;
        } catch (RuntimeException e) {
            log.warn("读取天气统计失败", e);
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> splitsMap(RunSplits splits) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", splits.runId());
        result.put("date", splits.date());
        result.put("splitMeters", splits.splitMeters());
        result.put("gpsDistanceKm", splits.gpsDistanceKm());
        result.put("movingDurationSeconds", splits.movingDurationSeconds());
        result.put("movingDurationText", splits.movingDurationText());
        result.put("avgPaceSecondsPerKm", splits.avgPaceSecondsPerKm());
        result.put("avgPaceText", splits.avgPaceText());
        result.put("avgGradeAdjustedPaceSecondsPerKm", splits.avgGradeAdjustedPaceSecondsPerKm());
        result.put("avgGradeAdjustedPaceText", splits.avgGradeAdjustedPaceText());
        result.put("fastest", splits.fastest());
        result.put("slowest", splits.slowest());
        result.put("firstHalfPaceSecondsPerKm", splits.firstHalfPaceSecondsPerKm());
        result.put("secondHalfPaceSecondsPerKm", splits.secondHalfPaceSecondsPerKm());
        result.put("negativeSplit", splits.negativeSplit());
        result.put("paceStdDevSeconds", splits.paceStdDevSeconds());
        result.put("splits", splits.splits());
        return result;
    }

    private Map<String, Object> quality(RunDataset dataset) {
        List<RunActivity> activities = dataset.activities();
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("warningCount", dataset.warnings().size());
        quality.put("warningCategories", warningCategories(dataset.warnings()));
        quality.put("zeroDistanceRunIds", ids(activities, run -> run.distanceMeters() <= 0));
        quality.put("missingPaceRunIds", ids(activities, run -> run.avgPaceSecondsPerKm() == null));
        quality.put("noGpsRunIds", ids(activities, run -> run.startLatitude() == null));
        quality.put("missingAltitudeRunIds", ids(activities, run -> run.minAltitudeMeters() == null));
        Map<String, Long> duplicates = activities.stream()
                .map(RunActivity::activityId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, LinkedHashMap::new, Collectors.counting()));
        quality.put("duplicateActivityIds", duplicates.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList());
        quality.put("paceOutliers", paceOutliers(activities));
        quality.put("duplicateTimestampRunIds", duplicateTimestampRunIds(activities));
        boolean clean = dataset.warnings().isEmpty()
                && ((List<?>) quality.get("zeroDistanceRunIds")).isEmpty()
                && ((List<?>) quality.get("missingPaceRunIds")).isEmpty()
                && ((List<?>) quality.get("noGpsRunIds")).isEmpty()
                && ((List<?>) quality.get("paceOutliers")).isEmpty()
                && ((List<?>) quality.get("duplicateTimestampRunIds")).isEmpty();
        quality.put("clean", clean);
        return quality;
    }

    /** 把 “文件名: 告警” 归并成类别计数，便于判断问题集中在哪类字段。 */
    private static Map<String, Long> warningCategories(List<String> warnings) {
        Map<String, Long> categories = new LinkedHashMap<>();
        for (String warning : warnings) {
            categories.merge(warningCategory(warning), 1L, Long::sum);
        }
        return categories;
    }

    private static String warningCategory(String warning) {
        String message = warning == null ? "" : warning;
        int separator = message.indexOf(": ");
        if (separator >= 0) {
            message = message.substring(separator + 2);
        }
        if (message.contains("缺少 Time")) {
            return "missing-time";
        }
        if (message.contains("Time 无法解析")) {
            return "bad-time";
        }
        if (message.contains("缺少经纬度")) {
            return "missing-position";
        }
        if (message.contains("(0,0)")) {
            return "zero-position";
        }
        if (message.contains("缺少 AltitudeMeters")) {
            return "missing-altitude";
        }
        if (message.contains("数字字段")) {
            return "invalid-number";
        }
        if (message.contains("TotalTimeSeconds")) {
            return "missing-lap-duration";
        }
        if (message.contains("DistanceMeters")) {
            return "missing-lap-distance";
        }
        if (message.contains("失败") || message.contains("没有 Activity") || message.contains("无法确定开始时间")) {
            return "parse-error";
        }
        if (message.contains("没有找到")) {
            return "no-files";
        }
        return "other";
    }

    /** 平均配速与区间中位数偏差超过阈值的记录，通常是记录错误或包含大量停顿。 */
    private List<Map<String, Object>> paceOutliers(List<RunActivity> activities) {
        List<Double> paces = activities.stream()
                .map(RunActivity::avgPaceSecondsPerKm)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (paces.size() < MIN_RUNS_FOR_PACE_OUTLIER) {
            return List.of();
        }
        double median = median(paces);
        List<Map<String, Object>> outliers = new ArrayList<>();
        for (RunActivity run : activities) {
            Double pace = run.avgPaceSecondsPerKm();
            if (pace == null || median <= 0) {
                continue;
            }
            double deviation = (pace - median) / median * 100d;
            if (Math.abs(deviation) < PACE_OUTLIER_PERCENT) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.id());
            row.put("date", run.date());
            row.put("paceSecondsPerKm", pace);
            row.put("paceText", PaceFormat.pace(pace));
            row.put("medianPaceSecondsPerKm", PaceFormat.round(median, 1));
            row.put("deviationPercent", PaceFormat.round(deviation, 1));
            outliers.add(row);
        }
        return outliers;
    }

    /** 同一记录内出现重复时间戳的记录 id；重复时间会让分段与最佳努力的插值失真。 */
    private List<String> duplicateTimestampRunIds(List<RunActivity> activities) {
        List<String> duplicates = new ArrayList<>();
        for (RunActivity run : activities) {
            if (run.trackPointCount() < 2) {
                continue;
            }
            Set<java.time.LocalDateTime> seen = new HashSet<>();
            for (TrackPoint point : repository.route(run.id())) {
                if (point != null && point.timestamp() != null && !seen.add(point.timestamp())) {
                    duplicates.add(run.id());
                    break;
                }
            }
        }
        return duplicates;
    }

    private static double median(List<Double> sorted) {
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2d;
    }

    private List<String> ids(List<RunActivity> activities, java.util.function.Predicate<RunActivity> predicate) {
        return activities.stream().filter(predicate).map(RunActivity::id).toList();
    }

    /**
     * 单次详情的完整字段。原先直接返回 record，时间会被序列化成 ISO-8601；
     * 这里显式展开，保证 {@code get_run_detail} 和列表接口的时间格式一致。
     */
    private Map<String, Object> toActivityDetail(RunActivity run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.id());
        row.put("activityId", run.activityId());
        row.put("sport", run.sport());
        row.put("date", run.date());
        row.put("startTime", formatDateTime(run.startTime()));
        row.put("endTime", formatDateTime(run.endTime()));
        row.put("startTimeUtc", formatDateTime(run.startTimeUtc()));
        row.put("durationSeconds", run.durationSeconds());
        row.put("distanceMeters", run.distanceMeters());
        row.put("climbMeters", run.climbMeters());
        row.put("descentMeters", run.descentMeters());
        row.put("routeType", run.routeType());
        row.put("routeTypeText", run.routeTypeText());
        row.put("trackPointCount", run.trackPointCount());
        row.put("avgSpeedKmh", run.avgSpeedKmh());
        row.put("avgPaceSecondsPerKm", run.avgPaceSecondsPerKm());
        row.put("startLatitude", run.startLatitude());
        row.put("startLongitude", run.startLongitude());
        row.put("endLatitude", run.endLatitude());
        row.put("endLongitude", run.endLongitude());
        row.put("minAltitudeMeters", run.minAltitudeMeters());
        row.put("maxAltitudeMeters", run.maxAltitudeMeters());
        row.put("sourceFile", run.sourceFile());
        row.put("creator", run.creator());
        return row;
    }

    private Map<String, Object> toStopPoint(StopPoint stop) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("startTime", formatDateTime(stop.startTime()));
        row.put("endTime", formatDateTime(stop.endTime()));
        row.put("durationSeconds", stop.durationSeconds());
        row.put("durationText", stop.durationText());
        row.put("latitude", stop.latitude());
        row.put("longitude", stop.longitude());
        row.put("altitudeMeters", stop.altitudeMeters());
        return row;
    }

    private Map<String, Object> toRunRow(RunActivity run) {
        if (run == null) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.id());
        row.put("date", run.date());
        row.put("sport", run.sport());
        row.put("startTime", formatDateTime(run.startTime()));
        row.put("distanceKm", PaceFormat.round(run.distanceMeters() / 1000d, 2));
        row.put("durationMinutes", PaceFormat.round(run.durationSeconds() / 60d, 1));
        row.put("durationText", PaceFormat.duration(run.durationSeconds()));
        row.put("paceSecondsPerKm", run.avgPaceSecondsPerKm());
        row.put("paceText", PaceFormat.pace(run.avgPaceSecondsPerKm()));
        row.put("speedKmh", run.avgSpeedKmh());
        row.put("climbMeters", run.climbMeters());
        row.put("descentMeters", run.descentMeters());
        row.put("routeType", run.routeType());
        row.put("routeTypeText", run.routeTypeText());
        row.put("trackPointCount", run.trackPointCount());
        return row;
    }

    private Map<String, Object> toRoutePage(Page<TrackPoint> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("offset", page.offset());
        result.put("limit", page.limit());
        result.put("hasMore", page.hasMore());
        result.put("points", page.items().stream().map(this::toRoutePoint).toList());
        return result;
    }

    /** 轨迹点的对外结构：海拔按米保留 2 位小数，压掉导出文件里的浮点噪声；经纬度保持原精度。 */
    private Map<String, Object> toRoutePoint(TrackPoint point) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", formatDateTime(point.timestamp()));
        row.put("latitude", point.latitude());
        row.put("longitude", point.longitude());
        row.put("altitudeMeters", point.altitudeMeters() == null
                ? null
                : PaceFormat.round(point.altitudeMeters(), 2));
        return row;
    }

    private String summaryText(RunSummary summary) {
        return "区间 " + summary.from() + " ~ " + summary.to() + " 共 " + summary.runCount() + " 次跑步，"
                + "总距离 " + summary.totalDistanceKm() + " 公里，"
                + "总时长 " + summary.totalDurationMinutes() + " 分钟，"
                + "平均配速 " + summary.avgPaceText() + "，"
                + "总爬升 " + summary.totalClimbMeters() + " 米";
    }

    /** 按步长抽取，始终保留首尾点，最多返回 maxPoints 个。 */
    private static List<TrackPoint> downsample(List<TrackPoint> points, Integer maxPoints) {
        if (maxPoints == null || maxPoints <= 0 || points.size() <= maxPoints) {
            return points;
        }
        int limit = Math.min(maxPoints, MAX_ROUTE_LIMIT);
        if (limit <= 1) {
            return List.of(points.getFirst());
        }
        List<TrackPoint> sampled = new ArrayList<>(limit);
        double step = (points.size() - 1d) / (limit - 1d);
        for (int i = 0; i < limit; i++) {
            sampled.add(points.get((int) Math.round(i * step)));
        }
        return sampled;
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

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "all";
        }
        String key = category.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "1k", "best1k", "best_1k", "best1km" -> "best1k";
            case "5k", "best5k", "best_5k", "best5km" -> "best5k";
            default -> Set.of("distance", "pace", "duration", "climb").contains(key) ? key : "all";
        };
    }

    private Map<String, Object> noData(DateRange range, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "区间 " + range.text() + " 内没有匹配的跑步记录。"
                + "可先用 list_runs 查看全部数据，或放宽日期与过滤条件。");
        if (detail != null) {
            result.put("detail", detail);
        }
        result.put("from", range.from());
        result.put("to", range.to());
        return result;
    }

    private Map<String, Object> noData(RunActivity run, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "记录 " + run.id() + " 缺少所需数据。");
        if (detail != null) {
            result.put("detail", detail);
        }
        result.put("runId", run.id());
        return result;
    }

    private Map<String, Object> noData(String runId, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "记录 " + runId + " 缺少所需数据。");
        if (detail != null) {
            result.put("detail", detail);
        }
        result.put("runId", runId);
        return result;
    }

    private Map<String, Object> noGps(String runId, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_GPS");
        result.put("message", "记录 " + runId + " 没有可用的 GPS 轨迹，无法完成该分析。");
        if (detail != null) {
            result.put("detail", detail);
        }
        result.put("runId", runId);
        return result;
    }

    private Map<String, Object> notFound(String runId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NOT_FOUND");
        result.put("message", "没有找到跑步记录 " + runId + "，请先用 list_runs 获取可用的 id");
        return result;
    }

    private Map<String, Object> ambiguous(String runId, RunLookup lookup) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "AMBIGUOUS");
        result.put("message", "记录 id \"" + runId + "\" 匹配到 " + lookup.candidates().size()
                + " 条记录，请改用更完整的 id");
        result.put("candidates", lookup.candidateIds());
        return result;
    }

    private int pageSize(Integer limit) {
        int value = limit == null || limit <= 0 ? properties.defaultPageSize() : limit;
        return Math.min(value, properties.maxPageSize());
    }

    private int routePageSize(Integer limit) {
        int value = limit == null || limit <= 0 ? 200 : limit;
        return Math.min(value, MAX_ROUTE_LIMIT);
    }

    private static int orZero(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}
