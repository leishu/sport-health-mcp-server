package org.example.sporthealth.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.BestEfforts;
import org.example.sporthealth.domain.Consistency;
import org.example.sporthealth.domain.DailyRunSummary;
import org.example.sporthealth.domain.DynamicsSegment;
import org.example.sporthealth.domain.DynamicsSummary;
import org.example.sporthealth.domain.ElevationProfile;
import org.example.sporthealth.domain.OfficialRaceComparison;
import org.example.sporthealth.domain.OfficialSplit;
import org.example.sporthealth.domain.OfficialSplitComparison;
import org.example.sporthealth.domain.OfficialSplitDelta;
import org.example.sporthealth.domain.OfficialSplits;
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
import org.example.sporthealth.domain.RunLevel;
import org.example.sporthealth.domain.RunLevelHistory;
import org.example.sporthealth.domain.RunLookup;
import org.example.sporthealth.domain.RunSplit;
import org.example.sporthealth.domain.RunSplits;
import org.example.sporthealth.domain.RunSummary;
import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.domain.RunningDynamics;
import org.example.sporthealth.domain.RunningDynamicsDetail;
import org.example.sporthealth.domain.SplitCrossCheck;
import org.example.sporthealth.domain.StopPoint;
import org.example.sporthealth.domain.StopPoints;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.domain.TrainingMix;
import org.example.sporthealth.domain.TrainingTrend;
import org.example.sporthealth.domain.VendorSplits;
import org.example.sporthealth.domain.WeatherImpact;
import org.example.sporthealth.domain.WeatherSnapshot;
import org.example.sporthealth.repository.HealthDataRepository;
import org.example.sporthealth.repository.HuaweiExportImporter;
import org.example.sporthealth.repository.JdbcHealthDataRepository;
import org.example.sporthealth.repository.RunRows;
import org.example.sporthealth.repository.RunWeatherStore;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.service.RunLevelService;
import org.example.sporthealth.service.RunningDynamicsService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.HuaweiStreamTypes;
import org.example.sporthealth.support.HuaweiSummaryData;
import org.example.sporthealth.support.PaceFormat;
import org.example.sporthealth.support.WeatherImpactAnalyzer;
import org.example.sporthealth.weather.WeatherBackfill;
import org.example.sporthealth.weather.WeatherCodes;
import org.example.sporthealth.weather.WindDirections;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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
 *       {@code AMBIGUOUS}（id 匹配到多条）、{@code INVALID_ARGUMENT}（参数值非法，需要调用方改参数）。</li>
 *   <li><b>参数校验口径</b>：不传 = 用默认值；传了但值非法（非正数、NaN、超出取值范围、无法识别的枚举）
 *       一律返回 {@code INVALID_ARGUMENT}，不会静默替换成默认值——否则返回的数据与请求对不上，
 *       调用方无从发现。返回体里带 {@code param} / {@code value} / 可选 {@code allowedValues}。
 *       刻意保留的宽容行为只有分页：{@code offset} 负数按 0、{@code limit} 非正数按默认值，
 *       且生效值一定在返回体里回显（{@code offset} / {@code limit}）。</li>
 *   <li>分页统一使用 {@code offset}/{@code limit}，列表与轨迹各有独立上限。</li>
 * </ul>
 */
@Component
@Slf4j
public class SportHealthTools {

    private static final int MAX_ROUTE_LIMIT = 5000;
    /** {@code rs} 流的 {@code v} 实测单位是 0.1 m/s，乘 0.36 得到 km/h（与活动平均速度吻合）。 */
    private static final double RS_SPEED_TO_KMH = 0.36d;
    private static final double DEFAULT_SPLIT_KM = 1d;
    /**
     * 分段距离的下限（公里）。再小切出来的段数会随距离线性膨胀（42 公里按 0.01 公里切就是 4200 段），
     * 返回体本身失去可读性，也不符合「分段配速」的用途，所以低于这个值直接拒绝。
     */
    private static final double MIN_SPLIT_KM = 0.1d;
    /** {@code get_personal_bests} 的 {@code category} 允许值，用于 {@code INVALID_ARGUMENT} 的提示。 */
    private static final List<String> CATEGORY_VALUES = List.of(
            "all", "distance", "pace", "duration", "climb",
            "best1k", "best3k", "best5k", "best10k", "best15k", "half", "marathon");
    /** {@code list_runs} 的 {@code sortBy} 允许值。 */
    private static final List<String> SORT_VALUES = List.of("date", "distance", "duration", "pace", "climb");
    /** {@code get_daily_running} 的 {@code groupBy} 允许值。 */
    private static final List<String> GROUP_VALUES = List.of("day", "week", "month");
    /** {@code get_training_trend} 的 {@code period} 允许值。 */
    private static final List<String> PERIOD_VALUES = List.of("week", "month");
    /** {@code get_running_dynamics} 的 {@code groupBy} 允许值；{@code none} 表示不出趋势。 */
    private static final List<String> DYNAMICS_GROUP_VALUES = List.of("month", "week", "none");
    /** 跑姿记录列表的默认与最大返回条数。 */
    private static final int DYNAMICS_ROW_DEFAULT = 50;
    private static final int DYNAMICS_ROW_MAX = 200;
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
    /** 可选：同理，非 JDBC 仓储没有天气表，也就没有可触发的回填。 */
    private final WeatherBackfill weatherBackfill;
    /** 可选：同理，非 JDBC 仓储没有 {@code run_level} 表。官方跑力是增强信息，缺失不影响其它工具。 */
    private final RunLevelService runLevelService;
    /** 跑姿/步幅分析；只需仓储，构造时可从 {@code repository} 兜底自建，单测不必额外注入。 */
    private final RunningDynamicsService runningDynamics;

    /**
     * 不带官方跑力、也不带天气回填的构造方式，供只有 TCX 数据的内存仓储与单测使用。
     */
    public SportHealthTools(RunAnalyticsService analytics,
                            HealthDataRepository repository,
                            HealthDataProperties properties,
                            RunWeatherStore weatherStore) {
        this(analytics, repository, properties, weatherStore, null, null);
    }

    /**
     * 不带天气回填的构造方式：天气只读（{@code get_run_detail} 的天气块、覆盖率），
     * {@code sync_weather} 返回 {@code NO_DATA} 而不是炸掉。
     */
    public SportHealthTools(RunAnalyticsService analytics,
                            HealthDataRepository repository,
                            HealthDataProperties properties,
                            RunWeatherStore weatherStore,
                            RunLevelService runLevelService) {
        this(analytics, repository, properties, weatherStore, null, runLevelService);
    }

    public SportHealthTools(RunAnalyticsService analytics,
                            HealthDataRepository repository,
                            HealthDataProperties properties,
                            RunWeatherStore weatherStore,
                            WeatherBackfill weatherBackfill,
                            RunLevelService runLevelService) {
        this(analytics, repository, properties, weatherStore, weatherBackfill, runLevelService, null);
    }

    /**
     * 完整构造方式。{@code runningDynamics} 允许为 {@code null}：它只依赖仓储，
     * 传 null 时按 {@code repository} 自建，这样既有的单测构造方式不用改。
     */
    @Autowired
    public SportHealthTools(RunAnalyticsService analytics,
                            HealthDataRepository repository,
                            HealthDataProperties properties,
                            RunWeatherStore weatherStore,
                            WeatherBackfill weatherBackfill,
                            RunLevelService runLevelService,
                            RunningDynamicsService runningDynamics) {
        this.analytics = analytics;
        this.repository = repository;
        this.properties = properties;
        this.weatherStore = weatherStore;
        this.weatherBackfill = weatherBackfill;
        this.runLevelService = runLevelService;
        this.runningDynamics = runningDynamics == null
                ? new RunningDynamicsService(repository)
                : runningDynamics;
    }

    /** 区间内的记录列表，新的在前，支持过滤、排序与分页。 */
    @McpTool(name = "list_runs",
            description = "按日期区间列出跑步记录，返回每次跑步的距离、时长、配速、爬升与轨迹点数。"
                    + "华为来源的记录还带平均/最高/最低心率、平均步频、步数、热量与华为运动类型码，"
                    + "TCX 来源的记录这些字段为 null。"
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
        Map<String, Object> invalid = validateListArgs(sortBy, weekday, minDistanceKm, maxDistanceKm,
                minPaceSecondsPerKm, maxPaceSecondsPerKm);
        if (invalid != null) {
            return invalid;
        }
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
                    + "风向风速、天气现象）；华为来源的记录还带平均/最高/最低心率、平均步频、步数、热量、"
                    + "华为运动类型码，以及 summaryData 的可用性（原文交给 get_run_summary_data，"
                    + "未进轨迹点的原始流交给 get_run_streams）。"
                    + "可选按页返回 GPS 轨迹点。runId 先用 list_runs 获取。"
                    + "用于回答“这次跑步的天气怎么样”“那天多少度”“这次心率多少”。")
    public Map<String, Object> getRunDetail(
            @McpToolParam(description = "跑步记录 id，先用 list_runs 获取") String runId,
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

    /**
     * 华为 {@code summaryData} 的结构化视图。
     *
     * <p>{@code get_run_detail} 只说明「这条记录带 summaryData」，2.5 KB 原文里
     * {@code bestPace}、{@code britishPaceMap}、跑姿汇总这些字段没人接手，等于白存。
     * 这里把实测确认过的字段换算成带单位的值，剩下没建模的仍可按 {@code fields} 取原文。
     *
     * <p>换算只做确认过的那部分：{@code britishPaceMap}/{@code bpm2} 是英制单英里口径
     * （距离轴是千分之一英里、配速是秒/英里），原样保留并写进 {@code notes}，
     * 英里分段由 {@code get_official_splits} 的 {@code unit=mile} 负责，这里不冒充公里配速。
     */
    @McpTool(name = "get_run_summary_data",
            description = "读取某次跑步的华为 summaryData（导出原文，约 2.5 KB JSON）并做单位换算："
                    + "时长/距离/热量、平均与最快配速、心率与步频、单公里分段 kmSplits（每段配速、"
                    + "本段用时、累计用时）、心率区间分布、跑姿汇总（触地时间、垂直振幅、左右平衡）、"
                    + "训练效果与恢复时间，以及手机自己记的天气 deviceWeather。"
                    + "未建模字段用 fields 取原文，逗号分隔，支持 a.b 与 a.*，例如 "
                    + "fields=\"bestPace,wearSportData.recovery_time,mExtendTrackDataMap.hrZ1\"。"
                    + "带 -1/-101/3.4e38 的占位值会转成缺省，并在 notes 里说明原因。"
                    + "TCX 来源的记录没有这个字段，返回 NO_DATA。")
    public Map<String, Object> getRunSummaryData(
            @McpToolParam(description = "跑步记录 id，先用 list_runs 获取") String runId,
            @McpToolParam(description = "只取哪些原始字段，逗号分隔，支持 a.b 与 a.*；不传返回全部",
                    required = false) String fields,
            @McpToolParam(description = "是否返回 decoded 换算块，默认 true", required = false) Boolean decode) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        String text = run.summaryData();
        if (text == null || text.isBlank()) {
            return noData(run, "这条记录没有华为 summaryData（TCX 来源的导出不含这个字段），"
                    + "可改用 get_run_detail / get_run_splits 的已建模字段");
        }
        Map<String, Object> raw = HuaweiSummaryData.parse(text);
        if (raw.isEmpty()) {
            return noData(run, "summaryData 不是合法 JSON，无法解析");
        }
        List<String> missing = new ArrayList<>();
        Map<String, Object> selected = HuaweiSummaryData.select(raw, fields, missing);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.put("source", "huawei.summaryData");
        result.put("bytes", text.length());
        result.put("fieldCount", raw.size());
        result.put("fields", selected);
        if (decode == null || decode) {
            List<String> notes = new ArrayList<>();
            result.put("decoded", HuaweiSummaryData.decode(raw, notes));
            result.put("notes", notes);
        }
        if (!missing.isEmpty()) {
            result.put("missingFields", missing);
            result.put("availableFields", List.copyOf(raw.keySet()));
        }
        return result;
    }

    /**
     * 暴露 {@code track_stream}：{@code detailData} 里没有逐点时间结构、因而不进轨迹点的采样。
     *
     * <p>不传 {@code type} 时只给目录（有哪些流、多少行、什么语义、数值范围、首行样例），
     * 因为一次跑步的 {@code rs} 可能有 2600 多行，直接全吐出来没有意义。传了 {@code type}
     * 再分页取行，行内的数值字段统一转成数字，{@code rs} 额外给出换算后的速度。
     *
     * <p>{@code pm2} 与 {@code p-m}（{@code bpm2} 与 {@code b-p-m}）是同一次采样的两种写法，
     * 传任一种都会命中存在的那个：{@code type} 是实际取到的类型，两者不同时另给
     * {@code requestedType} 与 {@code typeNote}。
     */
    @McpTool(name = "get_run_streams",
            description = "读取某次跑步的原始流 track_stream：华为导出里未进轨迹点的采样，"
                    + "包括每 5 秒速度 rs、跑姿 rp（触地时间 gct、垂直振幅 vo、左右平衡 gtb）、"
                    + "华为官方单公里配速 pm2、事件 evt 等。不传 type 时返回该记录的流目录"
                    + "（行数、语义、单位、数值范围、首行样例），先看清有什么再取；"
                    + "传 type 后按 offset/limit 分页取行（默认 200 行，最多 5000）。"
                    + "适合回答「后半程步频掉没掉」「每公里官方配速怎么算的」。"
                    + "只想看跑姿的均值、分段与前后半程对比，用 get_running_dynamics，不用自己拉原始行。")
    public Map<String, Object> getRunStreams(
            @McpToolParam(description = "跑步记录 id，先用 list_runs 获取") String runId,
            @McpToolParam(description = "流类型：rs / rp / pm2 / p-m / bpm2 / evt …；不传只返回目录",
                    required = false) String type,
            @McpToolParam(description = "分页偏移，默认 0", required = false) Integer offset,
            @McpToolParam(description = "返回行数，默认 200，最多 5000", required = false) Integer limit) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        List<TrackStream> all = repository.streams(run.id(), null);
        if (all.isEmpty()) {
            return noData(run, "这条记录没有原始流（TCX 来源不产生 track_stream，"
                    + "或华为导出里本来就没有这些采样）");
        }
        String requested = HuaweiStreamTypes.canonical(type);
        if (requested == null || requested.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("runId", run.id());
            result.put("date", run.date());
            result.put("totalRows", all.size());
            result.put("streams", streamCatalog(all));
            result.put("hint", "先看 type，再传 type 取具体行（例如 type=\"rs\"），分页用 offset/limit");
            return result;
        }

        String resolved = resolveStreamType(all, requested);
        List<TrackStream> rows = all.stream()
                .filter(row -> resolved.equals(HuaweiStreamTypes.canonical(row.streamType())))
                .toList();
        if (rows.isEmpty()) {
            Map<String, Object> result = noData(run, "没有 " + requested + " 这类流");
            result.put("availableTypes", all.stream()
                    .map(row -> HuaweiStreamTypes.canonical(row.streamType()))
                    .distinct()
                    .toList());
            return result;
        }

        Page<TrackStream> page = Page.of(rows, orZero(offset), routePageSize(limit));
        HuaweiStreamTypes.Kind kind = HuaweiStreamTypes.of(resolved);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.put("type", resolved);
        if (!resolved.equals(requested)) {
            result.put("requestedType", requested);
            result.put("typeNote", requested + " 与 " + resolved + " 是同一次采样的两种写法，已取 " + resolved);
        }
        result.put("meaning", kind.meaning());
        if (kind.unit() != null) {
            result.put("unit", kind.unit());
        }
        result.put("total", page.total());
        result.put("offset", page.offset());
        result.put("limit", page.limit());
        result.put("hasMore", page.hasMore());
        result.put("rows", page.items().stream().map(row -> toStreamRow(resolved, row)).toList());
        return result;
    }

    /**
     * 跑姿与步幅：触地时间、垂直振幅、左右平衡、步幅、步频。
     *
     * <p>这条工具把「跑姿」从原始流（{@code get_run_streams} 的 {@code rp}）提升成可回答问题的指标：
     * 触地时间/垂直振幅/左右平衡取华为 summaryData 的官方整次均值，步幅由「距离 ÷ 步数」折算，
     * 步频直接取华为的平均步频。传 {@code runId} 看单次（按公里分段 + 前后半程对比），
     * 不传则按日期区间汇总（覆盖度、均值与波动、按周/月趋势、逐次列表）。
     *
     * <p>跑姿三项只有连接过跑步动态传感器的记录才有，覆盖率必须显式给出，
     * 否则调用方会把「这半年没戴传感器」误读成「跑姿变好了」。
     */
    @McpTool(name = "get_running_dynamics",
            description = "查询跑步动态（跑姿）指标：平均触地时间、垂直振幅、触地平衡（左右平衡）、步幅与步频。"
                    + "传 runId 看单次：整次均值 + 按 splitKm（默认 1 公里）分段 + 前后半程对比（halves.delta 为后半程减前半程）；"
                    + "不传 runId 则按日期区间汇总：coverage（有多少次跑步真的带跑姿数据、覆盖率）、"
                    + "summary（各项的平均/最小/最大）、trend（按 groupBy=month/week 聚合，none 不出）、"
                    + "逐次记录列表与分页。"
                    + "触地时间/垂直振幅/左右平衡只有连接跑步动态传感器的记录才有（没戴就没有，不是跑姿变差）；"
                    + "步幅没有原始字段，用「总距离 ÷ 总步数」折算，分段/半程用「本段速度 × 60 ÷ 本段步频」。"
                    + "用于回答“平均触地时间多少”“垂直振幅大不大”“左右平衡偏不偏”“步幅怎么样”“后半程跑姿有没有散”。")
    public Map<String, Object> getRunningDynamics(
            @McpToolParam(description = "跑步记录 id；传了只看这一次，不传则按区间汇总", required = false) String runId,
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd；只在区间汇总时使用", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd；只在区间汇总时使用", required = false) String endDate,
            @McpToolParam(description = "区间趋势粒度：month（默认）、week，none 表示不返回趋势",
                    required = false) String groupBy,
            @McpToolParam(description = "单次模式的分段距离（公里），默认 1，最小 0.1；区间汇总不要传",
                    required = false) Double splitKm,
            @McpToolParam(description = "区间模式记录列表的偏移，默认 0", required = false) Integer offset,
            @McpToolParam(description = "区间模式返回的记录条数，默认 50，最多 200", required = false) Integer limit) {
        boolean single = runId != null && !runId.isBlank();
        if (single && (startDate != null || endDate != null)) {
            return invalidArgument("runId", runId,
                    "传了 runId 就只查这一次记录，不要再传 startDate/endDate；要按区间汇总就别传 runId", null);
        }
        if (single && groupBy != null && !groupBy.isBlank()) {
            return invalidArgument("groupBy", groupBy,
                    "groupBy 只用于区间汇总（不传 runId 时）；单次详情本来就只有一个周期", DYNAMICS_GROUP_VALUES);
        }
        if (!single && splitKm != null) {
            return invalidArgument("splitKm", splitKm,
                    "splitKm 只用于单次详情（传了 runId 时）；区间汇总返回逐次记录，不切段", null);
        }
        if (splitKm != null && !validSplitKm(splitKm)) {
            return invalidSplitKm(splitKm);
        }
        String group = normalizeDynamicsGroup(groupBy);
        if (group == null) {
            return invalidArgument("groupBy", groupBy,
                    "无法识别的趋势粒度 \"" + groupBy + "\"，支持 month / week / none；不传按 month",
                    DYNAMICS_GROUP_VALUES);
        }
        if (single) {
            return runningDynamicsOfRun(runId, splitKm == null ? DEFAULT_SPLIT_KM : splitKm);
        }
        return runningDynamicsOfRange(startDate, endDate, group, orZero(offset), limit);
    }

    /** 单次详情：整次汇总 + 分段 + 前后半程。 */
    private Map<String, Object> runningDynamicsOfRun(String runId, double splitKm) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        RunningDynamicsDetail detail = runningDynamics.detail(run, splitKm * 1000d);
        if (!detail.run().hasAnyMetric()) {
            return noData(run, "这条记录没有跑姿数据（触地时间/垂直振幅/左右平衡需要连接跑步动态传感器，"
                    + "track_stream 里也没有 rp 流），也没有步数可以折算步幅");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("scope", "run");
        result.put("runId", detail.run().runId());
        result.put("date", detail.run().date());
        result.put("splitMeters", detail.splitMeters());
        result.put("summary", dynamicsRow(detail.run()));
        result.put("metrics", dynamicsMetricHints());
        List<String> notes = new ArrayList<>();
        if (!detail.run().hasPosture()) {
            result.put("message", "这条记录没有触地时间/垂直振幅/左右平衡（需要连接跑步动态传感器），"
                    + "能给的只有步幅与步频");
            notes.add("步幅由「总距离 ÷ 总步数」折算，与跑姿传感器无关");
        }
        if (detail.segments().isEmpty()) {
            notes.add("轨迹点不足（少于 2 个有效坐标），无法按距离分段；整次汇总仍然有效");
        } else {
            result.put("segments", detail.segments());
            result.put("halves", dynamicsHalves(detail));
            notes.add("按距离分段时，跑姿采样（每 5 秒一个）用轨迹插值换算成累计距离后归段，"
                    + "段内没有采样的那一段三项为 null，不用邻近段顶替");
            notes.add("分段步幅 = 本段速度 × 60 ÷ 本段平均步频；整次步幅用「总距离 ÷ 总步数」，两者口径略有差异");
            notes.add("halves.delta 是后半程减前半程：触地时间变长、垂直振幅变大、步幅变小，"
                    + "通常说明疲劳后动作开始变形");
        }
        result.put("notes", notes);
        return result;
    }

    /** 区间汇总：覆盖度 + 均值波动 + 趋势 + 逐次列表。 */
    private Map<String, Object> runningDynamicsOfRange(String startDate, String endDate,
                                                       String group, int offset, Integer limit) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        List<RunActivity> runs = analytics.runs(range);
        if (runs.isEmpty()) {
            return noData(range, null);
        }
        List<RunningDynamics> rows = runningDynamics.ofRuns(runs);
        if (rows.isEmpty()) {
            Map<String, Object> result = noData(range, "区间内 " + runs.size()
                    + " 次跑步既没有跑姿数据（需要连接跑步动态传感器），也没有可折算步幅的步数");
            result.put("totalRuns", runs.size());
            return result;
        }
        DynamicsSummary summary = runningDynamics.summarize(rows);
        int total = rows.size();
        int from = Math.min(offset, total);
        int size = dynamicsPageSize(limit);
        List<RunningDynamics> page = rows.subList(from, Math.min(from + size, total));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("scope", "range");
        result.put("range", range.text());
        result.put("totalRuns", runs.size());
        result.put("coverage", dynamicsCoverage(runs.size(), summary));
        result.put("summary", dynamicsSummary(summary));
        result.put("metrics", dynamicsMetricHints());
        if (!"none".equals(group)) {
            result.put("groupBy", group);
            result.put("trend", runningDynamics.trend(rows, group));
        }
        result.put("total", total);
        result.put("offset", from);
        result.put("limit", size);
        result.put("hasMore", from + page.size() < total);
        result.put("runs", page.stream().map(this::dynamicsRow).toList());

        List<String> notes = new ArrayList<>();
        notes.add("统计口径：先算每次跑步的均值，再对这些均值取平均与极值；长跑不会因为 5 秒一个采样点就压过短跑，"
                + "所以与单次详情里的采样均值可能差 0.1 左右");
        notes.add("summary 覆盖整个区间，不受 offset/limit 分页影响；total 是 `runs` 列表里的记录数"
                + "（有跑姿或步幅的那些），totalRuns 是区间内全部跑步数");
        notes.add("步幅没有直接字段，用「总距离 ÷ 总步数」折算，因此没有跑姿数据但带步数的记录也有步幅");
        notes.add("触地平衡以左右各 50% 为均衡；华为没公开这个数值对应哪只脚，只看偏离 50 的幅度");
        if (summary.postureRunCount() == 0) {
            result.put("message", "区间内没有任何记录带触地时间/垂直振幅/左右平衡（需要连接跑步动态传感器），"
                    + "只能给出步幅与步频");
        }
        result.put("notes", notes);
        return result;
    }

    /** 覆盖率：多少条真的带跑姿数据，第一次/最后一次是哪天，以及一句该怎么读的结论。 */
    private static Map<String, Object> dynamicsCoverage(int totalRuns, DynamicsSummary summary) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("runs", totalRuns);
        coverage.put("dynamicsRuns", summary.postureRunCount());
        coverage.put("strideRuns", summary.strideRunCount());
        coverage.put("dynamicsPercent", totalRuns == 0
                ? null
                : PaceFormat.round(summary.postureRunCount() * 100d / totalRuns, 1));
        coverage.put("hint", dynamicsCoverageHint(totalRuns, summary.postureRunCount(), summary.strideRunCount()));
        return coverage;
    }

    private static String dynamicsCoverageHint(int totalRuns, int postureRuns, int strideRuns) {
        if (totalRuns == 0) {
            return "区间内没有跑步记录。";
        }
        if (postureRuns == 0) {
            return "区间内 " + totalRuns + " 次跑步都没有跑姿数据：触地时间/垂直振幅/左右平衡需要连接跑步动态传感器"
                    + "并在运动时开启跑姿采集，没戴就是没有，不代表跑姿变差。步幅依赖步数，"
                    + (strideRuns > 0 ? "仍有 " + strideRuns + " 次可算。" : "同样没有。");
        }
        if (postureRuns == totalRuns) {
            return "区间内 " + totalRuns + " 次跑步全部带跑姿数据，可以直接看均值与趋势。";
        }
        return "区间内 " + totalRuns + " 次跑步有 " + postureRuns + " 次带跑姿数据（"
                + PaceFormat.round(postureRuns * 100d / totalRuns, 1) + "%），其余记录只有步幅与步频；"
                + "比较前后期跑姿时先确认两边都戴了传感器。";
    }

    /** 一项指标的分布转成 JSON，count 为 0 时返回 null（不返回 0 值冒充「没有偏差」）。 */
    private static Map<String, Object> statMap(DynamicsSummary.MetricStat stat) {
        if (stat == null || stat.count() == 0) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", stat.count());
        map.put("average", stat.average());
        map.put("min", stat.min());
        map.put("max", stat.max());
        return map;
    }

    private static Map<String, Object> dynamicsSummary(DynamicsSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runCount", summary.runCount());
        map.put("postureRunCount", summary.postureRunCount());
        map.put("strideRunCount", summary.strideRunCount());
        putIfNotNull(map, "groundContactTimeMs", statMap(summary.groundContactTimeMs()));
        putIfNotNull(map, "verticalOscillationCm", statMap(summary.verticalOscillationCm()));
        putIfNotNull(map, "groundBalancePercent", statMap(summary.groundBalancePercent()));
        putIfNotNull(map, "strideLengthMeters", statMap(summary.strideLengthMeters()));
        putIfNotNull(map, "cadenceSpm", statMap(summary.cadenceSpm()));
        return map;
    }

    /** 单次记录的字段摊平：缺的字段不占位，不用 0 冒充。 */
    private Map<String, Object> dynamicsRow(RunningDynamics row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", row.runId());
        map.put("date", row.date());
        map.put("distanceKm", row.distanceKm());
        map.put("paceSecondsPerKm", row.paceSecondsPerKm());
        map.put("paceText", row.paceText());
        putIfNotNull(map, "cadenceSpm", row.cadenceSpm());
        putIfNotNull(map, "strideLengthMeters", row.strideLengthMeters());
        putIfNotNull(map, "groundContactTimeMs", row.groundContactTimeMs());
        putIfNotNull(map, "verticalOscillationCm", row.verticalOscillationCm());
        putIfNotNull(map, "groundBalancePercent", row.groundBalancePercent());
        if (row.sampleCount() > 0) {
            map.put("sampleCount", row.sampleCount());
        }
        return map;
    }

    /** 前后半程对比：两条分段 + 差值（后半程 − 前半程，正数表示后半程更大）。 */
    private static Map<String, Object> dynamicsHalves(RunningDynamicsDetail detail) {
        DynamicsSegment first = detail.firstHalf();
        DynamicsSegment second = detail.secondHalf();
        if (first == null || second == null) {
            return Map.of();
        }
        Map<String, Object> halve = new LinkedHashMap<>();
        halve.put("first", first);
        halve.put("second", second);
        Map<String, Object> delta = new LinkedHashMap<>();
        putDelta(delta, "paceSecondsPerKm", first.paceSecondsPerKm(), second.paceSecondsPerKm());
        putDelta(delta, "groundContactTimeMs", first.groundContactTimeMs(), second.groundContactTimeMs());
        putDelta(delta, "verticalOscillationCm",
                first.verticalOscillationCm(), second.verticalOscillationCm());
        putDelta(delta, "groundBalancePercent", first.groundBalancePercent(), second.groundBalancePercent());
        putDelta(delta, "strideLengthMeters", first.strideLengthMeters(), second.strideLengthMeters());
        putDelta(delta, "cadenceSpm", toDouble(first.cadenceSpm()), toDouble(second.cadenceSpm()));
        halve.put("delta", delta);
        return halve;
    }

    private static void putDelta(Map<String, Object> target, String key, Double first, Double second) {
        if (first == null || second == null) {
            return;
        }
        target.put(key, PaceFormat.round(second - first, 2));
    }

    private static Double toDouble(Integer value) {
        return value == null ? null : value.doubleValue();
    }

    /** 跑姿指标的读法：单位、方向与常见范围。数值本身不判断「好/坏」，由读的人结合配速看。 */
    private static Map<String, Object> dynamicsMetricHints() {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("groundContactTimeMs",
                dynamicsHint("ms", "lower", "脚着地到离地的时间；配速相近时越短，跑步经济性越好。业余跑者常见 200~300 ms"));
        hints.put("verticalOscillationCm",
                dynamicsHint("cm", "lower", "跑步时身体上下起伏的幅度；越大越费能量，常见 6~12 cm"));
        hints.put("groundBalancePercent",
                dynamicsHint("%", "balanced", "左右脚触地时间占比，50 表示左右均衡；偏差超过 2 个百分点才值得留意"));
        hints.put("strideLengthMeters",
                dynamicsHint("m", "context", "步幅 = 距离 ÷ 步数；配速相近时步幅变大通常意味着步频偏低"));
        hints.put("cadenceSpm",
                dynamicsHint("步/分", "context", "步频；常见 160~190 步/分，与步幅互为倒数关系"));
        return hints;
    }

    private static Map<String, Object> dynamicsHint(String unit, String betterWhen, String meaning) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("unit", unit);
        hint.put("betterWhen", betterWhen);
        hint.put("meaning", meaning);
        return hint;
    }

    /** {@code groupBy} 归一化：不传按 month；认不出返回 null，交给调用方报 INVALID_ARGUMENT。 */
    private static String normalizeDynamicsGroup(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "month";
        }
        String key = groupBy.trim().toLowerCase(Locale.ROOT);
        return DYNAMICS_GROUP_VALUES.contains(key) ? key : null;
    }

    /** 跑姿列表的分页大小：非正数按默认值，超出上限按上限（与其它列表工具一致）。 */
    private static int dynamicsPageSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return DYNAMICS_ROW_DEFAULT;
        }
        return Math.min(limit, DYNAMICS_ROW_MAX);
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** 流目录：按类型聚合行数、数值范围与首行样例，先看清有什么再决定取哪个。 */
    private List<Map<String, Object>> streamCatalog(List<TrackStream> all) {
        Map<String, List<TrackStream>> grouped = new LinkedHashMap<>();
        for (TrackStream row : all) {
            grouped.computeIfAbsent(HuaweiStreamTypes.canonical(row.streamType()), key -> new ArrayList<>())
                    .add(row);
        }
        List<Map<String, Object>> catalog = new ArrayList<>(grouped.size());
        grouped.forEach((type, rows) -> {
            HuaweiStreamTypes.Kind kind = HuaweiStreamTypes.of(type);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("rows", rows.size());
            entry.put("meaning", kind.meaning());
            if (kind.unit() != null) {
                entry.put("unit", kind.unit());
            }
            if (kind.equivalent() != null) {
                entry.put("equivalentType", kind.equivalent());
            }
            DoubleSummaryStatistics values = rows.stream()
                    .map(TrackStream::value)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .summaryStatistics();
            if (values.getCount() > 0) {
                Map<String, Object> valueRange = new LinkedHashMap<>();
                valueRange.put("min", PaceFormat.round(values.getMin(), 3));
                valueRange.put("avg", PaceFormat.round(values.getAverage(), 3));
                valueRange.put("max", PaceFormat.round(values.getMax(), 3));
                entry.put("valueRange", valueRange);
            }
            entry.put("firstSeq", rows.get(0).seq());
            entry.put("lastSeq", rows.get(rows.size() - 1).seq());
            entry.put("sample", toStreamRow(type, rows.get(0)));
            catalog.add(entry);
        });
        return catalog;
    }

    /**
     * 请求的类型不存在时退回等价写法（{@code pm2} ↔ {@code p-m}），都没有则原样返回，
     * 由调用方给出 NO_DATA 与可选类型清单。
     */
    private static String resolveStreamType(List<TrackStream> all, String requested) {
        java.util.Set<String> present = all.stream()
                .map(row -> HuaweiStreamTypes.canonical(row.streamType()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (present.contains(requested)) {
            return requested;
        }
        String equivalent = HuaweiStreamTypes.equivalent(requested);
        return equivalent != null && present.contains(equivalent) ? equivalent : requested;
    }

    /**
     * 一行原始流：数值字段转成数字，去掉重复的 {@code tp}；
     * {@code pm2}/{@code p-m} 另给段终点距离，{@code p-m}/{@code b-p-m} 的复合键拆回点序号。
     */
    private Map<String, Object> toStreamRow(String type, TrackStream row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", row.seq());
        if (row.timeMillis() != null) {
            out.put("time", formatDateTime(Instant.ofEpochMilli(row.timeMillis())));
            out.put("timeMillis", row.timeMillis());
        }
        Map<String, Object> fields = streamFields(row);
        boolean flatPace = "p-m".equals(type) || "b-p-m".equals(type);
        Integer pointIndex = row.pointIndex();
        if (pointIndex != null) {
            if (flatPace && pointIndex >= 10_000) {
                // p-m 的 k 是「距离米 × 10000 + 点序号」的复合键，拆开才有意义
                out.put("trackPointIndex", pointIndex % 10_000);
            } else {
                out.put("pointIndex", pointIndex);
            }
        }
        if (row.value() != null) {
            out.put("value", PaceFormat.round(row.value(), 3));
        }
        if (!fields.isEmpty()) {
            out.put("fields", fields);
        }
        switch (type) {
            case "rs" -> {
                if (row.value() != null) {
                    // 用未取整的值算配速，避免 9.96 km/h 这种中间结果把秒数推偏 1 秒
                    double speedKmh = row.value() * RS_SPEED_TO_KMH;
                    out.put("speedKmh", PaceFormat.round(speedKmh, 2));
                    out.put("paceText", PaceFormat.pace(speedKmh > 0 ? 3600d / speedKmh : null));
                }
            }
            case "pm2", "p-m" -> {
                Double endMeters = streamNumber(row.fields().get("d"));
                if (endMeters == null && flatPace && pointIndex != null && pointIndex >= 10_000) {
                    endMeters = (double) (pointIndex / 10_000);
                }
                if (endMeters != null) {
                    out.put("endDistanceMeters", PaceFormat.round(endMeters, 1));
                }
                out.put("paceText", PaceFormat.pace(row.value()));
            }
            default -> {
                // 其它流的字段含义未确认，只给原值
            }
        }
        return out;
    }

    /**
     * 流字段的原文都是字符串；能转成数字的就转，避免消费方再解析一遍。
     *
     * <p>与 {@code value}/{@code pointIndex}/{@code time} 完全重复的键会被去掉
     * （{@code v}/{@code p} 与 {@code value}、{@code k}/{@code n} 与点序号、{@code st} 与时间戳）：
     * 一次跑步的 {@code rs} 有五百多行，每行多背三个重复字段纯属噪声。
     */
    private static Map<String, Object> streamFields(TrackStream row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.fields().forEach((key, value) -> {
            if ("tp".equals(key)) {
                return;
            }
            Double number = streamNumber(value);
            if (isRedundantField(key, number, row)) {
                return;
            }
            out.put(key, number == null ? value : number);
        });
        return out;
    }

    private static boolean isRedundantField(String key, Double number, TrackStream row) {
        if (number == null) {
            return false;
        }
        return switch (key) {
            case "v", "p" -> row.value() != null && Math.abs(number - row.value()) < 1e-6;
            case "k", "n" -> row.pointIndex() != null && Math.abs(number - row.pointIndex()) < 1e-6;
            case "st" -> row.timeMillis() != null && Math.abs(number - row.timeMillis()) < 1e-6;
            default -> false;
        };
    }

    private static Double streamNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 只取轨迹点，适合分析路线或绘制地图；轨迹按需解析后缓存。
     *
     * <p>逐点心率、步频、累计距离来自华为导出（TCX 没有这些字段，恒为 {@code null}）。默认返回，
     * 用来回答「这段上坡心率多少」「后半程步频掉没掉」；只画地图时可以传 {@code includeSensors=false}
     * 退回纯位置数据以缩小返回体积。
     *
     * <p>GeoJSON 的坐标只能是数字，传感器数据放在顶级 {@code sensors} 块里，是与
     * {@code geometry.coordinates} 等长、同序的平行数组（无坐标的点会被过滤，两个数组始终对齐）。
     */
    @McpTool(name = "get_run_route",
            description = "分页读取某次跑步的 GPS 轨迹点：时间、纬度、经度、海拔，以及逐点心率 heartRate、"
                    + "步频 cadence、累计距离 distanceMeters（后三者仅华为来源的记录有，TCX 来源为 null）。"
                    + "用于分析路线、配速变化、坡段心率或后半程步频漂移，也可绘制轨迹。"
                    + "支持 format=geojson 直接返回 LineString（传感器放在与坐标等长的 sensors 平行数组里），"
                    + "或用 maxPoints 降采样控制返回体积，避免一次拉取上千个点；只画地图时可传 includeSensors=false 缩小体积。")
    public Map<String, Object> getRunRoute(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "分页偏移，默认 0；使用 maxPoints 时忽略", required = false) Integer offset,
            @McpToolParam(description = "返回条数，默认 200，最多 5000；使用 maxPoints 时忽略", required = false) Integer limit,
            @McpToolParam(description = "返回格式：points（默认）或 geojson", required = false) String format,
            @McpToolParam(description = "降采样到最多多少个点；设置后忽略 offset/limit", required = false) Integer maxPoints,
            @McpToolParam(description = "是否返回逐点心率/步频/累计距离，默认 true；只要位置数据时传 false",
                    required = false) Boolean includeSensors) {
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
        boolean withSensors = includeSensors == null || includeSensors;

        if ("geojson".equalsIgnoreCase(format)) {
            List<TrackPoint> sampled = downsample(route, maxPoints);
            // 先过滤再映射，保证 sensors 的平行数组与 coordinates 一一对齐
            List<TrackPoint> located = sampled.stream().filter(TrackPoint::hasPosition).toList();
            List<List<Double>> coordinates = located.stream()
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
            if (withSensors) {
                result.put("sensors", sensorSeries(located));
            }
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
        result.putAll(toRoutePage(page, withSensors));
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
                missing.put("message", "这条记录还没有回填天气，调用 sync_weather 补一轮即可（只补天气，不重导数据）");
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
        String mode = normalizeGroup(groupBy);
        if (mode == null) {
            return invalidArgument("groupBy", groupBy,
                    "无法识别的分组粒度 \"" + groupBy + "\"，支持 day / week / month；不传按 day",
                    GROUP_VALUES);
        }
        DateRange range = analytics.resolveRange(startDate, endDate);
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
                    + "以及区间内最快的最佳努力（默认 1 公里与 5 公里，基于 GPS 轨迹逐条解析）。"
                    + "distancesKm 可改成任意目标距离，例如 [1,5,10] 或 [1,5,10,21.0975,42.195]；"
                    + "只想看某一项时用 category，距离类别支持 best1k/best3k/best5k/best10k/best15k/half/marathon；"
                    + "distancesKm 或 category 取值非法时返回 INVALID_ARGUMENT，不会悄悄换成默认值。"
                    + "用于回答“我最好成绩是多少”“我 10 公里最快多少”“我半马水平如何”。")
    public Map<String, Object> getPersonalBests(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd", required = false) String endDate,
            @McpToolParam(description = "类别：all（默认）、distance、pace、duration、climb，"
                    + "或距离类别 best1k/best3k/best5k/best10k/best15k/half/marathon", required = false) String category,
            @McpToolParam(description = "最佳努力的目标距离（公里），默认 [1,5]；传了就以它为准，"
                    + "例如 [1,5,10,21.0975,42.195]；每一项都必须是正数", required = false) List<Double> distancesKm) {
        // 参数校验先于取数：拿不到数据时，「参数写错了」比「没有数据」更值得先报
        String key = normalizeCategory(category);
        if (key == null) {
            return invalidArgument("category", category,
                    "无法识别的类别 \"" + category + "\"：距离类别支持 best1k/best3k/best5k/best10k/best15k/"
                            + "half/marathon（别名如 5k、全马、半马也可以），其余支持 all/distance/pace/duration/climb；"
                            + "不传等同 all", CATEGORY_VALUES);
        }
        if (distancesKm != null && !distancesKm.isEmpty()) {
            List<String> invalid = invalidDistances(distancesKm);
            if (!invalid.isEmpty()) {
                return invalidArgument("distancesKm", distancesKm,
                        "distancesKm 每一项都必须是正数（单位公里），以下取值非法：" + String.join("、", invalid)
                                + "。留空数组或不传才会回落到默认值，避免「传了 [-1] 却返回 [1,5] 的结果」", null);
            }
        }
        DateRange range = analytics.resolveRange(startDate, endDate);
        PersonalBests bests = analytics.bests(range);
        if (bests.runCount() == 0) {
            return noData(range, null);
        }
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

        List<Double> targetsKm = effortTargetsKm(key, distancesKm);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("category", key);
        if (!targetsKm.isEmpty()) {
            List<Double> targetsMeters = targetsKm.stream().map(km -> km * 1000d).toList();
            Map<Double, PersonalBestEffort> efforts = analytics.effortBests(range, targetsMeters).stream()
                    .collect(Collectors.toMap(PersonalBestEffort::targetMeters, effort -> effort));
            List<Double> missing = new ArrayList<>();
            for (double meters : targetsMeters) {
                PersonalBestEffort effort = efforts.get(meters);
                if (effort == null) {
                    missing.add(meters / 1000d);
                    continue;
                }
                bestRows.put(effortLabel(meters), effort);
            }
            result.put("effortDistancesKm", targetsKm);
            if (!missing.isEmpty()) {
                result.put("unavailableDistancesKm", missing);
            }
            if (efforts.isEmpty()) {
                result.put("message", "区间内没有可用 GPS 轨迹，或单次距离不足 "
                        + formatKm(targetsKm.get(0)) + " 公里，无法计算目标距离的最佳努力");
            }
        }
        result.put("bests", bestRows);
        return result;
    }

    /**
     * 比赛成绩预测：用区间内的最佳努力做 Riegel 外推。
     * 目标距离本身有实测成绩时直接返回实测值，并标记 {@code source=ACTUAL}。
     *
     * <p>{@code includeOfficial=true} 时额外附上华为官方跑力给出的同距离成绩，
     * 两组数字并列但各带来源标识（{@code HUAWEI_OFFICIAL} / {@code ACTUAL} / {@code RIEGEL}），
     * 不互相覆盖。官方数据按「区间结束日或之前最近一天」取，时效信息在 {@code official} 块里。
     */
    @McpTool(name = "predict_race_time",
            description = "根据区间内的最佳努力预测比赛成绩（Riegel 公式）。不传 distanceKm 时输出 5K、10K、"
                    + "半马、全马四档；传 distanceKm 时只预测该距离。每条预测都带参考距离、参考记录与置信度，"
                    + "参考成绩越接近目标距离越可靠。includeOfficial=true 时再返回一组华为官方跑力给出的"
                    + "同距离成绩（source=HUAWEI_OFFICIAL），与 Riegel 逐行对照并给出差值，两组口径不同、不要混用。"
                    + "用于回答“我现在的水平全马能跑多少”“目标 10K 该按什么配速跑”“官方预测和我实际成绩差多少”。")
    public Map<String, Object> predictRaceTime(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认取数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String endDate,
            @McpToolParam(description = "目标距离（公里）；不传则输出 5K/10K/半马/全马", required = false) Double distanceKm,
            @McpToolParam(description = "是否附带华为官方跑力的同距离成绩，默认 false", required = false) Boolean includeOfficial) {
        if (distanceKm != null && (!Double.isFinite(distanceKm) || distanceKm <= 0)) {
            // 过去 <=0 会静默退回「5K/10K/半马/全马」四档，用户以为在预测 0 公里却拿到四行结果
            return invalidArgument("distanceKm", distanceKm,
                    "distanceKm 必须是正数（单位公里），收到 " + distanceKm
                            + "；想看默认四档就不要传这个参数", null);
        }
        DateRange range = analytics.resolveRange(startDate, endDate);
        RacePrediction prediction = analytics.predictRace(range, distanceKm);
        boolean include = Boolean.TRUE.equals(includeOfficial);
        boolean hasRiegel = !prediction.predictions().isEmpty();
        if (!hasRiegel && !include) {
            return noData(range, prediction.text());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", hasRiegel ? "OK" : "NO_DATA");
        result.put("range", range.text());
        if (!hasRiegel) {
            result.put("message", "区间 " + range.text()
                    + " 内没有可用的 GPS 轨迹，Riegel 外推不可用；official 为华为官方跑力给出的成绩");
        }
        result.put("method", prediction.method());
        result.put("exponent", prediction.exponent());
        result.put("references", prediction.references());
        result.put("predictions", prediction.predictions());
        result.put("text", prediction.text());
        if (include) {
            result.put("official", officialBlock(prediction, range));
        }
        return result;
    }

    /**
     * 华为官方跑力：跑力值、A/E/I/M/R 五档配速与官方给的 1/3/5/10 公里、半马、全马成绩。
     *
     * <p>这是 {@code run_level} 表唯一的读取入口。与 {@link #predictRaceTime} 的关系是并列参照：
     * 官方值是华为对个人能力的评估，每天一行快照；Riegel 只反映所选区间内实际跑出的成绩。
     */
    @McpTool(name = "get_run_level",
            description = "读取华为官方跑力（导出的 user run level data）：跑力值、A/E/I/M/R 五档配速"
                    + "（速度已从 km/h 换算成配速），以及官方给出的 1/3/5/10 公里、半马、全马成绩。"
                    + "不传 date 时取最新一天；传 date 时取该日或之前最近一天（官方数据是每日快照）。"
                    + "includeHistory=true 时附跑力趋势，historyDays 控制窗口（默认 90 天）。"
                    + "预测比赛成绩请用 predict_race_time（includeOfficial=true 可与官方值逐项对照）。"
                    + "用于回答“我的华为跑力多少”“A/E/I/M/R 配速是多少”“官方预测我全马多少”“跑力这一年涨了多少”。")
    public Map<String, Object> getRunLevel(
            @McpToolParam(description = "日期，格式 yyyy-MM-dd；不传取最新一天，传了取该日或之前最近一天",
                    required = false) String date,
            @McpToolParam(description = "是否附带跑力趋势，默认 false", required = false) Boolean includeHistory,
            @McpToolParam(description = "趋势窗口天数，默认 90，最大 3650", required = false) Integer historyDays) {
        if (runLevelService == null) {
            return runLevelNoData("当前仓储不是 SQLite 版，没有 run_level 表，无法读取华为官方跑力");
        }
        LocalDate requested = date == null || date.isBlank() ? null : DateParser.parseDate(date);
        try {
            Optional<RunLevel> snapshot = requested == null
                    ? runLevelService.latest()
                    : runLevelService.latestOnOrBefore(requested);
            if (snapshot.isEmpty()) {
                return runLevelNoData(requested == null
                        ? "run_level 表为空，尚未导入华为官方跑力数据（重新 reload_data 可触发派生表重建）"
                        : "没有找到 " + requested + " 或之前的官方跑力数据");
            }
            RunLevel level = snapshot.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("date", level.date());
            result.put("source", RunLevelService.SOURCE);
            result.put("runLevel", level.score());
            result.put("zones", level.zones());
            result.put("officialTimes", level.officialTimes());
            result.put("text", runLevelService.describe(level));
            if (requested != null && !requested.equals(level.date())) {
                result.put("requestedDate", requested);
                result.put("note", requested + " 当天没有快照，已取之前最近一天 " + level.date());
            }
            if (Boolean.TRUE.equals(includeHistory)) {
                RunLevelHistory history = runLevelService.history(level.date(), historyDays);
                if (history != null) {
                    result.put("history", history);
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("读取华为官方跑力失败", e);
            return runLevelNoData("读取 run_level 失败：" + e.getMessage());
        }
    }

    /**
     * 官方跑力块：官方成绩与 Riegel 逐距离对照。
     *
     * <p>没有官方数据时返回嵌套的 {@code status=NO_DATA}，而不是让整个预测失败——
     * Riegel 结果本身仍然有效，官方值只是增强信息。
     */
    private Map<String, Object> officialBlock(RacePrediction prediction, DateRange range) {
        if (runLevelService == null) {
            return officialUnavailable("当前仓储不是 SQLite 版，没有 run_level 表");
        }
        OfficialRaceComparison comparison;
        try {
            comparison = runLevelService.compare(prediction, range);
        } catch (RuntimeException e) {
            log.warn("读取华为官方跑力失败", e);
            return officialUnavailable("读取 run_level 失败：" + e.getMessage());
        }
        if (comparison == null) {
            return officialUnavailable("没有 " + range.to() + " 或之前的官方跑力快照");
        }
        Map<String, Object> official = new LinkedHashMap<>();
        official.put("status", "OK");
        official.put("source", comparison.source());
        official.put("date", comparison.date());
        official.put("runLevel", comparison.score());
        official.put("withinRange", comparison.withinRange());
        official.put("staleDays", comparison.staleDays());
        official.put("predictions", comparison.rows());
        if (!comparison.unavailableTargetsKm().isEmpty()) {
            official.put("unavailableTargetsKm", comparison.unavailableTargetsKm());
        }
        official.put("text", comparison.text());
        return official;
    }

    private static Map<String, Object> officialUnavailable(String message) {
        Map<String, Object> official = new LinkedHashMap<>();
        official.put("status", "NO_DATA");
        official.put("source", RunLevelService.SOURCE);
        official.put("message", message);
        return official;
    }

    private static Map<String, Object> runLevelNoData(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("source", RunLevelService.SOURCE);
        result.put("message", message);
        return result;
    }

    /** 两次对比，结论直接回读给用户；任一 id 多义时返回 AMBIGUOUS。 */
    @McpTool(name = "compare_runs",
            description = "对比两次跑步的距离、时长与配速差异，并给出一句话结论（谁更快、快多少）。"
                    + "includeSplits=true 时附带分段配速（默认每 1 公里，可用 splitKm 调整），便于看配速分布。"
                    + "两次记录 id 都用 list_runs 获取。")
    public Map<String, Object> compareRuns(
            @McpToolParam(description = "基准跑步记录 id") String baseRunId,
            @McpToolParam(description = "对比跑步记录 id") String otherRunId,
            @McpToolParam(description = "是否附带两次的分段配速，默认 false", required = false) Boolean includeSplits,
            @McpToolParam(description = "分段距离（公里），默认 1，仅 includeSplits=true 时生效；"
                    + "非法值（≤0、NaN、小于 0.1）返回 INVALID_ARGUMENT", required = false) Double splitKm) {
        // splitKm 只在 includeSplits=true 时参与计算，但校验不看下游是否用到：
        // 同一个非法值在不同 includeSplits 下时好时坏，比直接拒绝更难排查
        if (!validSplitKm(splitKm)) {
            return invalidSplitKm(splitKm);
        }
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
            double stepMeters = splitMeters(splitKm);
            RunSplits baseSplits = analytics.splits(base, stepMeters);
            RunSplits otherSplits = analytics.splits(other, stepMeters);
            Map<String, Object> splits = new LinkedHashMap<>();
            // 键名与 get_run_splits 对齐，调用方用同一个字段就能核对两组结果的分段口径
            splits.put("splitMeters", stepMeters);
            splits.put("base", baseSplits.splits());
            splits.put("other", otherSplits.splits());
            // 没有 GPS 轨迹的那次仍然返回空数组，但要明确是哪一次缺，避免被当成「配速为 0」
            List<String> noGps = new ArrayList<>(2);
            if (baseSplits.splits().isEmpty()) {
                noGps.add(base.id());
            }
            if (otherSplits.splits().isEmpty()) {
                noGps.add(other.id());
            }
            if (!noGps.isEmpty()) {
                splits.put("noGpsRunIds", noGps);
            }
            result.put("splits", splits);
        }
        return result;
    }

    /**
     * 分段距离统一口径：未传用 {@link #DEFAULT_SPLIT_KM}，传了按公里转米。
     * {@code compare_runs} 与 {@code get_run_splits} 共用，调用前必须先过 {@link #validSplitKm}——
     * 这里不再兜底非法值，否则「传 0.5 公里却按 1 公里切」这类问题会重新变成静默行为。
     */
    private static double splitMeters(Double splitKm) {
        return splitKm == null ? DEFAULT_SPLIT_KM * 1000d : splitKm * 1000d;
    }

    /** 每公里（或自定义距离）分段配速。 */
    @McpTool(name = "get_run_splits",
            description = "把一次跑步按固定距离（默认 1 公里）切分，返回每段距离、用时、配速、爬升、净海拔变化、"
                    + "平均坡度与坡度修正配速（GAP），以及最快/最慢分段、前后半程配速、是否负分段、配速稳定性。"
                    + "GAP 把上坡折算成等效平路配速，便于山路和平路横向比较。"
                    + "华为来源的记录还会附带厂商官方单公里配速（vendorSplits，来自 track_stream 的 pm2），"
                    + "并与 GPS 分段做交叉校验（splitCrossCheck），便于判断轨迹是否可信。"
                    + "用于回答“每公里配速多少”“有没有跑崩”“后半程更快吗”“山路实际强度多大”“GPS 准不准”。")
    public Map<String, Object> getRunSplits(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "分段距离（公里），默认 1；非法值（≤0、NaN、小于 0.1）返回 INVALID_ARGUMENT",
                    required = false) Double splitKm) {
        if (!validSplitKm(splitKm)) {
            return invalidSplitKm(splitKm);
        }
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        double splitMeters = splitMeters(splitKm);
        RunSplits splits = analytics.splits(run, splitMeters);
        if (splits.splits().isEmpty()) {
            return noGps(run.id(), "没有可用的 GPS 轨迹，无法计算分段配速");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.putAll(splitsMap(splits));

        VendorSplits vendor = analytics.vendorSplits(run);
        if (vendor.usable()) {
            result.put("vendorSplits", vendorMap(vendor));
            SplitCrossCheck check = analytics.crossCheck(splits, vendor);
            if (check != null) {
                result.put("splitCrossCheck", check);
            }
        }
        return result;
    }

    /**
     * {@code get_official_splits} 的 {@code unit} 允许值：{@code both} 只在单次模式下有效。
     */
    private static final List<String> OFFICIAL_UNIT_VALUES = List.of("km", "mile", "both");
    /** 官方分段对比列表的默认与最大返回条数。 */
    private static final int OFFICIAL_ROW_DEFAULT = 50;
    private static final int OFFICIAL_ROW_MAX = 200;
    /** 段长容差（公里）：两侧段长差超过它就跳过，不硬比。 */
    private static final double OFFICIAL_TOLERANCE_KM = 0.05d;

    /**
     * 华为官方分段配速（设备自算）与 GPS 分段的逐段对比。
     *
     * <p>与 {@code get_run_splits} 的分工：后者是 GPS 口径的分段（含 GAP、爬升），官方分段只是
     * 附带一块汇总；这里反过来，官方分段是主角，并且逐段给出 GPS 对照与差值。
     */
    @McpTool(name = "get_official_splits",
            description = "读华为自己算好的分段配速，并与同一段的 GPS 分段逐段对比，回答"
                    + "“华为官方每公里配速与 GPS 分段差多少”“官方认为最快的那公里和 GPS 差多少”。"
                    + "unit=km（默认）用 pm2 的官方单公里分段；unit=mile 用 bpm2 的官方单英里分段"
                    + "（英制口径，1 英里 = 1609.344 米，段数约为公里数的 0.62 倍）；"
                    + "unit=both 同时给两套，只用于单次模式。"
                    + "传 runId 看单次：units.{km|mile} 里有官方每段的距离/用时/配速、"
                    + "同段 GPS 配速与 deltaSecondsPerKm（官方 − GPS，正数表示官方更慢）、"
                    + "累计差值 cumulativeDeltaSeconds、最快/最慢段，以及 comparison（平均绝对差、"
                    + "最大差出现在第几公里、差 ≤5 秒与 ≤15 秒的段数、一句话结论）。"
                    + "不传 runId 按日期区间汇总：coverage（多少条记录有官方分段）、summary（各次平均差"
                    + "的平均/中位数/P90、方向）与逐次记录分页。"
                    + "华为没有单独的“最佳配速”字段，官方最快段由本工具从官方分段里取最小值。")
    public Map<String, Object> getOfficialSplits(
            @McpToolParam(description = "跑步记录 id；传了只看这一次，不传则按区间汇总", required = false) String runId,
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd；只在区间汇总时使用", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd；只在区间汇总时使用", required = false) String endDate,
            @McpToolParam(description = "段单位：km（默认，pm2 单公里）、mile（bpm2 单英里）、"
                    + "both（两套都给，只用于单次）", required = false) String unit,
            @McpToolParam(description = "区间模式记录列表的偏移，默认 0", required = false) Integer offset,
            @McpToolParam(description = "区间模式返回的记录条数，默认 50，最多 200", required = false) Integer limit) {
        boolean single = runId != null && !runId.isBlank();
        String normalized = analyticsUnit(unit);
        if (normalized == null) {
            return invalidArgument("unit", unit,
                    "无法识别的段单位 \"" + unit + "\"，支持 km / mile / both；不传按 km", OFFICIAL_UNIT_VALUES);
        }
        if (single && (startDate != null || endDate != null)) {
            return invalidArgument("runId", runId,
                    "传了 runId 就只查这一次记录，不要再传 startDate/endDate；要按区间汇总就别传 runId", null);
        }
        if (!single && "both".equals(normalized)) {
            return invalidArgument("unit", unit,
                    "unit=both 只在单次模式（传 runId）下有效：区间汇总一次只按一种口径统计，"
                            + "要两套就分别查 km 与 mile", List.of("km", "mile"));
        }
        if (single) {
            return officialSplitsOfRun(runId, normalized);
        }
        return officialSplitsOfRange(startDate, endDate, normalized, orZero(offset), limit);
    }

    /** 段单位归一化：{@code both} 表示两套都要，其余交给分析层归一化。 */
    private static String analyticsUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return RunAnalyticsService.OFFICIAL_UNIT_KM;
        }
        String key = unit.trim().toLowerCase(Locale.ROOT);
        if ("both".equals(key)) {
            return "both";
        }
        return RunAnalyticsService.normalizeOfficialUnit(key);
    }

    /** 单次详情：官方分段 + GPS 逐段对照 + 汇总结论；{@code unit=both} 时两套口径并列。 */
    private Map<String, Object> officialSplitsOfRun(String runId, String unit) {
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        List<String> units = "both".equals(unit)
                ? List.of(RunAnalyticsService.OFFICIAL_UNIT_KM, RunAnalyticsService.OFFICIAL_UNIT_MILE)
                : List.of(unit);

        Map<String, Object> blocks = new LinkedHashMap<>();
        for (String candidate : units) {
            Map<String, Object> block = officialUnitBlock(run, candidate);
            if (block != null) {
                blocks.put(candidate, block);
            }
        }
        if (blocks.isEmpty()) {
            Map<String, Object> result = noData(run, "这条记录没有华为官方分段（track_stream 里没有 pm2 / bpm2 流），"
                    + "多半是 TCX 来源或室内跑；分段配速请改用 get_run_splits 看 GPS 口径");
            result.put("activityDistanceKm", PaceFormat.round(run.distanceMeters() / 1000d, 3));
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("scope", "run");
        result.put("runId", run.id());
        result.put("date", run.date());
        result.put("sport", run.sport());
        result.put("activityDistanceKm", PaceFormat.round(run.distanceMeters() / 1000d, 3));
        result.put("units", blocks);
        if (blocks.size() < units.size()) {
            int missing = units.size() - blocks.size();
            result.put("message", "有 " + missing + " 套口径没有数据：导出里缺对应的流"
                    + "（km 需要 pm2、mile 需要 bpm2），另一套仍然可用");
        }
        result.put("notes", officialSplitNotes());
        return result;
    }

    /**
     * 一套口径的完整块：官方分段（带 GPS 对照）、逐段对比结论、最快段对比。
     *
     * @return 该口径没有官方分段时返回 {@code null}
     */
    private Map<String, Object> officialUnitBlock(RunActivity run, String unit) {
        OfficialSplits official = analytics.officialSplits(run, unit);
        if (!official.usable()) {
            return null;
        }
        RunSplits gps = analytics.splits(run, official.unitMeters());
        OfficialSplitComparison comparison = analytics.officialComparison(gps, official, OFFICIAL_TOLERANCE_KM);

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("unit", official.unit());
        block.put("source", official.source());
        block.put("unitMeters", official.unitMeters());
        block.put("splitCount", official.splits().size());
        block.put("fullSplitCount", official.fullSplitCount());
        block.put("coveredKm", official.coveredKm());
        block.put("avgPaceSecondsPerUnit", official.avgPaceSecondsPerUnit());
        block.put("avgPaceText", official.avgPaceText());
        block.put("avgPaceSecondsPerKm", official.avgPaceSecondsPerKm());
        block.put("avgPaceTextPerKm", official.avgPaceTextPerKm());
        block.put("fastest", officialSplitRow(official.fastest(), null));
        block.put("slowest", officialSplitRow(official.slowest(), null));
        block.put("splits", officialSplitsWithGps(official, comparison));
        if (comparison == null) {
            block.put("message", "没有可用的 GPS 分段（无轨迹，或两侧段长都对不上），只能给官方分段本身");
        } else {
            block.put("comparison", comparisonMap(comparison));
            Map<String, Object> fastest = fastestComparison(official, gps);
            if (!fastest.isEmpty()) {
                block.put("fastestComparison", fastest);
            }
        }
        return block;
    }

    /** 官方分段逐段摊开，有 GPS 对照时把同段的 GPS 配速与差值并进去。 */
    private List<Map<String, Object>> officialSplitsWithGps(OfficialSplits official,
                                                           OfficialSplitComparison comparison) {
        Map<Integer, OfficialSplitDelta> deltas = comparison == null
                ? Map.of()
                : comparison.splits().stream()
                        .collect(Collectors.toMap(OfficialSplitDelta::index, delta -> delta, (a, b) -> a,
                                LinkedHashMap::new));
        return official.splits().stream()
                .map(split -> officialSplitRow(split, deltas.get(split.index())))
                .toList();
    }

    /** 一段官方分段的 JSON；{@code delta} 为 null 时不占位，不用 0 冒充「一致」。 */
    private Map<String, Object> officialSplitRow(OfficialSplit split, OfficialSplitDelta delta) {
        if (split == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", split.index());
        row.put("fromKm", split.fromKm());
        row.put("toKm", split.toKm());
        row.put("distanceKm", split.distanceKm());
        row.put("durationSeconds", split.durationSeconds());
        row.put("paceSecondsPerUnit", split.paceSecondsPerUnit());
        row.put("paceText", split.paceText());
        row.put("paceSecondsPerKm", split.paceSecondsPerKm());
        row.put("paceTextPerKm", split.paceTextPerKm());
        row.put("partial", split.partial());
        putIfNotNull(row, "pointIndex", split.pointIndex());
        if (delta != null) {
            row.put("gpsPaceSecondsPerKm", delta.gpsPaceSecondsPerKm());
            row.put("gpsPaceText", delta.gpsPaceText());
            row.put("deltaSecondsPerKm", delta.deltaSecondsPerKm());
            row.put("deltaText", delta.deltaText());
            row.put("cumulativeDeltaSeconds", delta.cumulativeDeltaSeconds());
        }
        return row;
    }

    /** 逐段对比的汇总：平均/最大差、差出现在哪、方向与分布，外加一句结论。 */
    private static Map<String, Object> comparisonMap(OfficialSplitComparison comparison) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("unit", comparison.unit());
        map.put("comparedSplits", comparison.comparedSplits());
        map.put("meanAbsDeltaSecondsPerKm", comparison.meanAbsDeltaSecondsPerKm());
        map.put("maxAbsDeltaSecondsPerKm", comparison.maxAbsDeltaSecondsPerKm());
        map.put("maxDeltaAtKm", comparison.maxDeltaAtKm());
        map.put("signedMeanDeltaSecondsPerKm", comparison.signedMeanDeltaSecondsPerKm());
        map.put("within5SplitCount", comparison.within5SplitCount());
        map.put("within15SplitCount", comparison.within15SplitCount());
        map.put("direction", comparison.signedMeanDeltaSecondsPerKm() > 0
                ? "官方整体比 GPS 慢" : "官方整体比 GPS 快");
        map.put("verdict", comparison.verdict());
        return map;
    }

    /** 官方最快段 vs GPS 最快段：两边口径不同，「最快」未必落在同一公里。 */
    private static Map<String, Object> fastestComparison(OfficialSplits official, RunSplits gps) {
        Map<String, Object> map = new LinkedHashMap<>();
        OfficialSplit officialFastest = official.fastest();
        RunSplit gpsFastest = gps == null ? null : gps.fastest();
        if (officialFastest != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromKm", officialFastest.fromKm());
            item.put("toKm", officialFastest.toKm());
            item.put("paceSecondsPerUnit", officialFastest.paceSecondsPerUnit());
            item.put("paceText", officialFastest.paceText());
            item.put("paceSecondsPerKm", officialFastest.paceSecondsPerKm());
            item.put("paceTextPerKm", officialFastest.paceTextPerKm());
            map.put("official", item);
        }
        if (gpsFastest != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromKm", gpsFastest.fromKm());
            item.put("toKm", gpsFastest.toKm());
            item.put("paceSecondsPerKm", gpsFastest.paceSecondsPerKm());
            item.put("paceText", gpsFastest.paceText());
            map.put("gps", item);
        }
        if (officialFastest != null && gpsFastest != null && gpsFastest.paceSecondsPerKm() != null) {
            double delta = PaceFormat.round(
                    officialFastest.paceSecondsPerKm() - gpsFastest.paceSecondsPerKm(), 1);
            map.put("deltaSecondsPerKm", delta);
            map.put("deltaText", RunAnalyticsService.splitDeltaText(delta));
        }
        return map;
    }

    /**
     * 区间汇总：覆盖度 + 各次平均差的整体分布 + 逐次记录。
     *
     * <p>口径与 {@code get_running_dynamics} 一致：先算每次的平均绝对差，再对这些值取统计，
     * 而不是把所有段混成一堆——否则长跑（段多）会压过短跑。
     */
    private Map<String, Object> officialSplitsOfRange(String startDate, String endDate, String unit,
                                                      int offset, Integer limit) {
        DateRange range = analytics.resolveRange(startDate, endDate);
        List<RunActivity> runs = analytics.runs(range);
        if (runs.isEmpty()) {
            return noData(range, null);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Double> meanAbsValues = new ArrayList<>();
        int officialRuns = 0;
        double maxAbsAll = 0d;
        for (RunActivity run : runs) {
            OfficialSplits official = analytics.officialSplits(run, unit);
            if (!official.usable()) {
                continue;
            }
            officialRuns++;
            RunSplits gps = analytics.splits(run, official.unitMeters());
            OfficialSplitComparison comparison = analytics.officialComparison(gps, official, OFFICIAL_TOLERANCE_KM);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", run.id());
            row.put("date", run.date());
            row.put("distanceKm", PaceFormat.round(run.distanceMeters() / 1000d, 3));
            row.put("officialAvgPaceSecondsPerUnit", official.avgPaceSecondsPerUnit());
            row.put("officialAvgPaceText", official.avgPaceText());
            row.put("officialAvgPaceSecondsPerKm", official.avgPaceSecondsPerKm());
            row.put("officialAvgPaceTextPerKm", official.avgPaceTextPerKm());
            if (comparison == null) {
                row.put("comparedSplits", 0);
                rows.add(row);
                continue;
            }
            meanAbsValues.add(comparison.meanAbsDeltaSecondsPerKm());
            maxAbsAll = Math.max(maxAbsAll, comparison.maxAbsDeltaSecondsPerKm());
            row.put("gpsAvgPaceSecondsPerKm", gps.avgPaceSecondsPerKm());
            row.put("gpsAvgPaceText", gps.avgPaceText());
            row.put("comparedSplits", comparison.comparedSplits());
            row.put("meanAbsDeltaSecondsPerKm", comparison.meanAbsDeltaSecondsPerKm());
            row.put("maxAbsDeltaSecondsPerKm", comparison.maxAbsDeltaSecondsPerKm());
            row.put("maxDeltaAtKm", comparison.maxDeltaAtKm());
            row.put("signedMeanDeltaSecondsPerKm", comparison.signedMeanDeltaSecondsPerKm());
            rows.add(row);
        }
        if (officialRuns == 0) {
            Map<String, Object> result = noData(range, "区间内 " + runs.size()
                    + " 次跑步都没有华为官方分段（track_stream 里没有 " 
                    + (RunAnalyticsService.OFFICIAL_UNIT_MILE.equals(unit) ? "bpm2" : "pm2")
                    + " 流），多半是 TCX 来源或室内跑");
            result.put("totalRuns", runs.size());
            return result;
        }

        int total = rows.size();
        int from = Math.min(offset, total);
        int size = officialPageSize(limit);
        List<Map<String, Object>> page = rows.subList(from, Math.min(from + size, total));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("scope", "range");
        result.put("range", range.text());
        result.put("from", range.from());
        result.put("to", range.to());
        result.put("unit", unit);
        result.put("totalRuns", runs.size());
        result.put("coverage", officialCoverage(runs.size(), officialRuns, meanAbsValues.size()));
        result.put("summary", officialRangeSummary(meanAbsValues, maxAbsAll));
        result.put("total", total);
        result.put("offset", from);
        result.put("limit", size);
        result.put("hasMore", from + page.size() < total);
        result.put("runs", page);
        result.put("notes", officialRangeNotes());
        return result;
    }

    private static Map<String, Object> officialCoverage(int totalRuns, int officialRuns, int comparedRuns) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("runs", totalRuns);
        coverage.put("officialRuns", officialRuns);
        coverage.put("comparedRuns", comparedRuns);
        coverage.put("officialPercent", totalRuns == 0
                ? null : PaceFormat.round(officialRuns * 100d / totalRuns, 1));
        String hint;
        if (officialRuns == 0) {
            hint = "区间内没有记录带官方分段：TCX 来源与室内跑不会有 pm2 / bpm2。";
        } else if (comparedRuns == 0) {
            hint = "有官方分段但都没法跟 GPS 对比（记录缺轨迹）。";
        } else if (officialRuns == totalRuns) {
            hint = "区间内 " + totalRuns + " 次跑步全部带官方分段，可以直接看整体差值。";
        } else {
            hint = "区间内 " + totalRuns + " 次跑步有 " + officialRuns + " 次带官方分段（"
                    + PaceFormat.round(officialRuns * 100d / totalRuns, 1) + "%），其余是 TCX 或室内跑；"
                    + "看整体差值时按 comparedRuns 理解样本量。";
        }
        coverage.put("hint", hint);
        return coverage;
    }

    /** 各次平均绝对差的分布：均值/中位数/P90/极值，加上方向与达标次数。 */
    private static Map<String, Object> officialRangeSummary(List<Double> meanAbsValues, double maxAbsAll) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int compared = meanAbsValues.size();
        summary.put("comparedRuns", compared);
        if (compared == 0) {
            summary.put("verdict", "没有可对比的记录，给不出整体差值");
            return summary;
        }
        List<Double> sorted = meanAbsValues.stream().sorted().toList();
        double mean = PaceFormat.round(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0d), 1);
        summary.put("meanAbsDeltaSecondsPerKm", mean);
        summary.put("medianAbsDeltaSecondsPerKm", percentile(sorted, 0.5));
        summary.put("p90AbsDeltaSecondsPerKm", percentile(sorted, 0.9));
        summary.put("maxAbsDeltaSecondsPerKm", maxAbsAll);
        long within5 = sorted.stream().filter(value -> value <= 5d).count();
        long above20 = sorted.stream().filter(value -> value > 20d).count();
        summary.put("runsWithin5Seconds", within5);
        summary.put("runsAbove20Seconds", above20);
        summary.put("verdict", officialRangeVerdict(mean, compared, (int) within5, (int) above20));
        return summary;
    }

    private static String officialRangeVerdict(double meanAbs, int comparedRuns, int within5, int above20) {
        String base = "共同可比 " + comparedRuns + " 次，平均绝对差 " + meanAbs + " 秒/公里";
        if (meanAbs <= 5d) {
            return base + "：两边几乎一致，GPS 算出来的配速可以放心当成绩用";
        }
        if (meanAbs <= 15d) {
            return base + "：差异属于口径与信号噪声的正常范围，看趋势不用纠偏";
        }
        return base + "（其中 " + above20 + " 次超过 20 秒、"
                + within5 + " 次在 5 秒以内）：先查差得大的那几次轨迹，"
                + "常见原因是隧道/楼间丢星、操场绕圈或折返段，而不是配速本身";
    }

    private static List<String> officialSplitNotes() {
        return List.of(
                "官方分段由设备累计距离切分，GPS 分段是沿轨迹插值切分，两者口径不同；"
                        + "差值稳定说明互相印证，忽大忽小通常是其中一方受信号影响",
                "deltaSecondsPerKm = 官方 − GPS：正数表示官方更慢；"
                        + "cumulativeDeltaSeconds 是到本段终点为止两边累计用时之差（秒）",
                "末段两侧长度常常不同（一边切在整单位、一边切在轨迹末尾），长度差超过 50 米的段会被跳过，"
                        + "所以 comparedSplits 可能小于分段数",
                "mile 口径来自英制导出的 bpm2（b-p-m），1 英里 = 1609.344 米；"
                        + "华为没有单独的“最佳配速”字段，fastest 是从官方分段里取的最小值");
    }

    private static List<String> officialRangeNotes() {
        return List.of(
                "summary 覆盖区间内全部有官方分段的记录，不受 offset/limit 分页影响；"
                        + "total 是 runs 列表的条数，totalRuns 是区间内全部跑步数",
                "统计口径：先算每次的平均绝对差，再对这些值取均值/中位数/P90，"
                        + "长跑不会因为段数多就压过短跑",
                "TCX 来源与室内跑没有 pm2 / bpm2，永远不会出现在 officialRuns 里");
    }

    private static int officialPageSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return OFFICIAL_ROW_DEFAULT;
        }
        return Math.min(limit, OFFICIAL_ROW_MAX);
    }

    /**
     * 已排序列表的分位数（线性插值），保留 1 位小数。
     *
     * <p>样本量小的时候（十几条记录里只有五六次可比）用插值比最近秩更稳，
     * 且 {@code p=0.5} 与中位数定义一致。
     */
    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0d;
        }
        if (sorted.size() == 1) {
            return PaceFormat.round(sorted.get(0), 1);
        }
        double position = Math.clamp(p, 0d, 1d) * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        double weight = position - lower;
        return PaceFormat.round(sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight, 1);
    }

    /** 一次跑步内 1K / 5K 等目标距离的最佳用时。 */
    @McpTool(name = "get_best_efforts",
            description = "计算一次跑步内指定目标距离（默认 1 公里、5 公里）的最佳用时与折算配速，"
                    + "例如“这 10 公里里最快的 5 公里是多少”。超过本次轨迹距离的目标会列在 unavailableTargetsMeters。"
                    + "distancesKm 里出现非正数或非数值时返回 INVALID_ARGUMENT，不会悄悄算别的距离。")
    public Map<String, Object> getBestEfforts(
            @McpToolParam(description = "跑步记录 id") String runId,
            @McpToolParam(description = "目标距离列表（公里），默认 [1, 5]；例如 [1,5,10]；"
                    + "每一项都必须是正数", required = false) List<Double> distancesKm) {
        if (distancesKm != null && !distancesKm.isEmpty()) {
            List<String> invalid = invalidDistances(distancesKm);
            if (!invalid.isEmpty()) {
                return invalidArgument("distancesKm", distancesKm,
                        "distancesKm 每一项都必须是正数（单位公里），以下取值非法：" + String.join("、", invalid)
                                + "；不传则默认 [1, 5]", null);
            }
        }
        RunLookup lookup = analytics.lookup(runId);
        if (lookup.isAmbiguous()) {
            return ambiguous(runId, lookup);
        }
        if (!lookup.isFound()) {
            return notFound(runId);
        }
        RunActivity run = lookup.run();
        // 走到这里 targets 一定非空，且全部为正数：默认 [1,5] 或调用方的合法列表（按原顺序去重）
        List<Double> targets = distancesKm == null || distancesKm.isEmpty()
                ? List.of(1000d, 5000d)
                : distancesKm.stream().distinct().map(km -> km * 1000d).toList();
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
            // 只有「目标都合法且都跑不到」才会走到这里，所以可以明说原因与最小的那个目标
            double smallestKm = targets.stream().mapToDouble(meters -> meters / 1000d).min().orElse(0d);
            result.put("message", "本次轨迹可用距离 " + efforts.gpsDistanceKm() + " 公里，"
                    + "小于最小目标距离 " + formatKm(smallestKm) + " 公里，"
                    + "所以所有目标都算不出来；可调小 distancesKm，或先用 list_runs 确认这次的实际距离");
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
        if (period != null && !period.isBlank() && !PERIOD_VALUES.contains(period.trim().toLowerCase(Locale.ROOT))) {
            // 过去除 month 之外一律当 week 处理，「按季度看」会拿到周趋势且毫无提示
            return invalidArgument("period", period,
                    "无法识别的周期粒度 \"" + period + "\"，支持 week / month；不传按 week", PERIOD_VALUES);
        }
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
     * 单次跑步的训练类型识别：配速/距离/分段波动为主，心率区间做交叉验证。
     */
    @McpTool(name = "classify_run",
            description = "识别一次跑步的训练类型：轻松跑、长距离跑、节奏跑、间歇/变速跑、比赛/测试跑或恢复跑。"
                    + "主判据是距离、时长、配速相对个人中位数与分段配速波动；华为来源的记录还会把平均心率换算成"
                    + "心率区间（静息心率取近 28 天均值、最大心率取数据里观察到的最快值，即储备心率口径）做交叉验证："
                    + "心率与配速一致时提高置信度，冲突时下调并在 reasons 里说明；配速像轻松跑但平均心率已进阈值区时"
                    + "改判为节奏跑。返回判定依据、心率区间与置信度。用于回答“这次算轻松跑还是节奏跑”。"
                    + "runId 先用 list_runs 获取。")
    public Map<String, Object> classifyRun(
            @McpToolParam(description = "跑步记录 id，先用 list_runs 获取") String runId) {
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
        result.put("heartRate", heartRateMap(classification.heartRate()));
        result.put("text", classification.text());
        return result;
    }

    /** 心率区间判定的输出形态；TCX 来源没有心率时整块为 null，键仍然存在。 */
    private Map<String, Object> heartRateMap(RunClassification.HeartRate heartRate) {
        if (heartRate == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("avgHeartRate", heartRate.avgHeartRate());
        map.put("maxHeartRate", heartRate.maxHeartRate());
        map.put("restingHeartRate", heartRate.restingHeartRate());
        map.put("maxHeartRateReference", heartRate.maxHeartRateReference());
        map.put("percentBasis", heartRate.percentBasis());
        map.put("percent", heartRate.percent());
        map.put("zone", heartRate.zone());
        map.put("zoneText", heartRate.zoneText());
        map.put("signal", heartRate.signal());
        map.put("text", heartRate.text());
        return map;
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
     *
     * <p>输出里的 {@code weatherCoverage} 是「天气到底齐不齐」的唯一入口：分组结果的样本数
     * 只统计同时具备配速与天气的跑步，样本少时如果不说清总量与缺口，调用方只能猜是数据少还是天气没补上。
     */
    @McpTool(name = "get_weather_impact",
            description = "分析天气对配速的影响：按起跑气温、湿度、风速、有无降水分组，返回每组的次数、"
                    + "加权平均配速、相对整体基线的快慢秒数，以及气温与配速的相关系数。"
                    + "同时返回 weatherCoverage（区间内跑步总数、拿到天气的条数、缺失条数及其原因拆分、覆盖率），"
                    + "天气不足时据此判断要不要先补数据。只使用已经回填的天气数据，不会联网；"
                    + "缺天气时调用 sync_weather 补一轮即可，不需要重导数据。"
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
        List<RunActivity> runs = analytics.runs(range);
        WeatherImpact impact;
        Map<String, Object> coverage;
        try {
            List<RunWeather> weatherRows = weatherStore.findAll();
            impact = WeatherImpactAnalyzer.analyze(range, runs, weatherRows, minDistance);
            coverage = weatherCoverage(runs, weatherRows);
        } catch (RuntimeException e) {
            log.warn("天气影响分析失败", e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NO_DATA");
            result.put("message", "读取天气数据失败：" + e.getMessage());
            result.put("range", range.text());
            return result;
        }
        if (impact.sampleSize() == 0) {
            // NO_DATA 恰恰最需要覆盖率：是「本来就没有跑步」还是「有跑步但天气全缺」，处理方式完全不同
            Map<String, Object> result = noData(range, impact.text());
            result.put("weatherCoverage", coverage);
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("totalRuns", impact.totalRuns());
        result.put("sampleSize", impact.sampleSize());
        result.put("weatherCoverage", coverage);
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

    /**
     * 区间内的天气覆盖率：总跑步数、拿到天气的条数、缺失条数与原因拆分。
     *
     * <p>口径与 {@link WeatherImpactAnalyzer} 一致：只认 {@code status = OK} 的行，
     * 而 {@code RunWeather.ok(...)} 落行时一定带快照，所以「状态 OK」与「有可用快照」等价。
     *
     * <p>{@code missing} = 总数 - OK，缺失再拆成三类，因为处理方式不同：
     * {@code failed} 可以重试（{@code sync_weather}）、{@code unavailable} 是数据源没这个时段
     * （重试无效）、{@code notFetched} 是压根还没回填过。
     *
     * <p>不受 {@code minDistanceKm} 影响：天气齐不齐是数据完整性问题，不该被分析过滤条件改变。
     */
    private static Map<String, Object> weatherCoverage(List<RunActivity> runs, List<RunWeather> weatherRows) {
        Map<String, RunWeather> byRunId = new LinkedHashMap<>();
        for (RunWeather row : weatherRows) {
            if (row != null && row.runId() != null) {
                byRunId.put(row.runId(), row);
            }
        }
        int ok = 0;
        int failed = 0;
        int unavailable = 0;
        int notFetched = 0;
        for (RunActivity run : runs) {
            RunWeather row = byRunId.get(run.id());
            if (row == null) {
                notFetched++;
                continue;
            }
            switch (row.status()) {
                case OK -> ok++;
                case FAILED -> failed++;
                case UNAVAILABLE -> unavailable++;
            }
        }
        int total = runs.size();
        int missing = total - ok;
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("totalRuns", total);
        coverage.put("ok", ok);
        coverage.put("missing", missing);
        coverage.put("failed", failed);
        coverage.put("unavailable", unavailable);
        coverage.put("notFetched", notFetched);
        coverage.put("coveragePercent", total == 0 ? null : PaceFormat.round(ok * 100d / total, 1));
        coverage.put("hint", coverageHint(total, ok, missing, failed, unavailable, notFetched));
        return coverage;
    }

    /** 覆盖率的结论：直接告诉调用方「要不要补、补了有没有用」。 */
    private static String coverageHint(int total, int ok, int missing, int failed, int unavailable, int notFetched) {
        if (total == 0) {
            return "区间内没有跑步记录，无从统计天气覆盖。";
        }
        if (missing == 0) {
            return "区间内 " + total + " 次跑步全部有天气数据。";
        }
        if (ok == 0) {
            return "区间内 " + total + " 次跑步都没有可用天气（失败 " + failed + "、无数据 " + unavailable
                    + "、未回填 " + notFetched + "）。先调用 sync_weather 联网补一轮；"
                    + "它只补天气、不重导数据，失败时会在返回里给出原因。";
        }
        return "区间内 " + total + " 次跑步有 " + ok + " 次拿到天气，" + missing + " 次缺失（失败 " + failed
                + "、无数据 " + unavailable + "、未回填 " + notFetched + "）。"
                + "失败的记录可用 sync_weather 立刻重试；UNAVAILABLE 表示数据源没有该时段数据，重试无效。";
    }

    /**
     * 数据源自检入口：文件、条数、日期范围、来源、RouteType、解析告警与数据质量。
     *
     * <p>两个计数口径必须分开看：{@code runningCount} 只含 {@code sport_family='RUN'}（跑步分析的对象），
     * {@code allWorkoutCount} 是库里全部运动记录（含步行等其它项目）。同理
     * {@code runSourceFileCount} 是跑步记录的来源文件去重数，与 {@code storage.scannedTcxFiles}
     * （本次扫描的 TCX 文件数）、{@code storage.health.scannedHuaweiFiles}（本次扫描的华为导出文件数）
     * 都不是一回事。
     */
    @McpTool(name = "describe_data_source",
            description = "查看当前数据源状态：数据目录；跑步记录数 runningCount（只含 RUN）与全部运动记录数 "
                    + "allWorkoutCount（含步行等其它项目）；跑步记录的来源文件 runSourceFiles / "
                    + "runSourceFileCount（去重后的文件名，不等于磁盘上的文件数——TCX 与华为导出 JSON/xls/xlsx "
                    + "的实际扫描数见 storage）；覆盖日期范围、累计距离、解析告警分类以及数据质量检查"
                    + "（0 距离、缺配速/坐标/海拔、重复 Activity Id、配速离群、重复时间戳；"
                    + "全部为空且无告警时 quality.clean 为 true）。"
                    + "数据看起来不对时先调用它排查；重复时间戳需要解析全部轨迹，首次调用稍慢。")
    public Map<String, Object> describeDataSource() {
        RunDataset dataset = repository.dataset();
        List<RunActivity> activities = dataset.activities();
        Map<String, Object> storage = storageInfo(healthInfo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("dataDir", dataset.dataDir());
        result.put("zoneId", properties.zoneId());
        result.put("loadedAt", formatDateTime(dataset.loadedAt()));
        result.put("runningCount", dataset.runCount());
        Integer allWorkouts = allWorkoutCount();
        if (allWorkouts != null) {
            result.put("allWorkoutCount", allWorkouts);
        }
        result.put("runSourceFileCount", dataset.sourceFiles().size());
        result.put("runSourceFiles", dataset.sourceFiles());
        result.put("warnings", dataset.warnings());
        result.put("storage", storage);

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

    /** 重新扫描并增量同步 TCX 与华为导出（JSON/xls/xlsx），默认随后触发天气回填；旧轨迹缓存随之丢弃。 */
    @McpTool(name = "reload_data",
            description = "重新扫描数据目录，把新增或内容变化的 TCX 与华为导出文件（JSON/xls/xlsx）增量同步进 "
                    + "SQLite，无需重启服务。未变化的文件按 size/mtime/sha256 跳过，不会重复解析；"
                    + "源文件已删除的历史记录会保留。默认在写完后顺带触发一轮天气回填；"
                    + "只想同步跑步数据、不想被天气请求的网络超时拖住时传 weather=false，"
                    + "之后再单独调用 sync_weather 补天气。")
    public Map<String, Object> reloadData(
            @McpToolParam(description = "是否顺带回填天气，默认 true；传 false 时整段同步不发起任何网络请求",
                    required = false) Boolean weather) {
        boolean withWeather = weather == null || weather;
        repository.reload(withWeather);
        RunDataset dataset = repository.dataset();
        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder message = new StringBuilder("已增量同步：跑步 ").append(dataset.runCount()).append(" 条");
        result.put("status", "OK");
        result.put("runningCount", dataset.runCount());
        Integer allWorkouts = allWorkoutCount();
        if (allWorkouts != null) {
            result.put("allWorkoutCount", allWorkouts);
            message.append("、全部运动 ").append(allWorkouts).append(" 条");
        }
        message.append(withWeather
                ? "；已触发天气回填，扫描与变更明细见 storage"
                : "；已跳过天气回填（weather=false），需要时调用 sync_weather，扫描与变更明细见 storage");
        result.put("message", message.toString());
        result.put("weatherRequested", withWeather);
        result.put("warnings", dataset.warnings());
        result.put("storage", storageInfo(healthInfo()));
        return result;
    }

    /**
     * 单独补一轮天气，不重导数据、不重建快照。
     *
     * <p>存在的理由：{@code reload_data} 的全量扫描 + 天气 HTTP 调用捆在一起，
     * 端点不可达时一次调用要等到多个超时，MCP 客户端大概率先断开；而天气失败又需要尽快重试
     * （默认的 7 天冷却只会让 3 条失败记录一直空着）。这个工具只做「补齐天气」这一件事，
     * 而且默认忽略冷却——手动调用本身就是「现在就去试一次」的明确意图。
     *
     * <p>失败不会影响任何跑步数据：每条记录单独落状态，{@code run_weather} 里
     * {@code FAILED} 的行下次还能再试，{@code UNAVAILABLE} 表示数据源没有该时段数据（终态）。
     */
    @McpTool(name = "sync_weather",
            description = "只补天气：为还没有天气的跑步记录调用 open-meteo 抓取起跑/结束时刻的天气并写入本地库，"
                    + "不重导跑步数据、不重建快照。返回本轮成功 ok、无数据 unavailable、失败 failed、"
                    + "缺坐标 noLocation、延后 deferred 的条数以及失败原因 problems，"
                    + "并附带同步后的整体覆盖率 coverage。默认会立即重试上次失败的记录（忽略冷却期），"
                    + "用于 reload_data(weather=false) 之后单独补天气，或失败后马上重试。"
                    + "用于回答“补一下天气”“天气怎么还没数据”。")
    public Map<String, Object> syncWeather(
            @McpToolParam(description = "是否立即重试上次抓取失败的记录（忽略 retry-failed-after-days 冷却），默认 true",
                    required = false) Boolean retryFailed) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (weatherBackfill == null || weatherStore == null) {
            result.put("status", "NO_DATA");
            result.put("message", "当前仓储没有天气表，无法补天气；天气只存在于 SQLite 数据源下。");
            return result;
        }
        if (!weatherBackfill.enabled()) {
            result.put("status", "DISABLED");
            result.put("message", "天气回填已被配置关闭（sport.weather.enabled=false），"
                    + "改回 true 并重启后再调用 sync_weather。");
            return result;
        }
        boolean force = retryFailed == null || retryFailed;
        long startedAt = System.nanoTime();
        WeatherBackfill.Result run;
        try {
            run = weatherBackfill.run(Instant.now(), force);
        } catch (RuntimeException e) {
            log.warn("手动补天气失败", e);
            result.put("status", "FAILED");
            result.put("message", "补天气时出错：" + e.getMessage() + "；跑步数据不受影响。");
            result.put("coverage", weatherInfo());
            return result;
        }
        result.put("status", "OK");
        result.put("retriedFailed", force);
        result.put("ok", run.ok());
        result.put("unavailable", run.unavailable());
        result.put("failed", run.failed());
        result.put("noLocation", run.noLocation());
        result.put("deferred", run.deferred());
        result.put("attempted", run.attempted());
        result.put("total", run.total());
        result.put("problems", run.problems());
        result.put("retryableProblems", run.retryableProblems());
        result.put("elapsedMillis", (System.nanoTime() - startedAt) / 1_000_000);
        result.put("message", syncWeatherText(run, force));
        result.put("coverage", weatherInfo());
        return result;
    }

    /**
     * 一轮补天气的结论。
     *
     * <p>三种「什么也没做」要分开说：没有候选（全部已是终态）、被短路延后、以及失败的记录。
     * 只回一句「完成」会让调用方以为天气已经齐了。
     */
    private static String syncWeatherText(WeatherBackfill.Result run, boolean retriedFailed) {
        if (run.total() == 0) {
            return "没有需要补天气的跑步记录：全部记录都已是 OK 或 UNAVAILABLE（数据源无该时段数据）。"
                    + "若仍有记录显示无天气，用 get_weather_impact 的 weatherCoverage 确认缺失原因。";
        }
        StringBuilder text = new StringBuilder("本轮处理 ").append(run.total()).append(" 条：成功 ")
                .append(run.ok()).append("、无数据 ").append(run.unavailable())
                .append("、失败 ").append(run.failed()).append("、缺坐标 ").append(run.noLocation())
                .append("、延后 ").append(run.deferred());
        if (run.failed() > 0) {
            text.append("。失败的记录保留 FAILED 状态，下次 sync_weather 会再试");
            if (!retriedFailed) {
                text.append("（本次遵守冷却期，刚失败的记录要等 retry-failed-after-days 天后才会被选中）");
            }
            if (!run.retryableProblems().isEmpty()) {
                text.append("；原因：").append(run.retryableProblems().get(0));
            }
            text.append("。");
        }
        if (run.deferred() > 0) {
            text.append("。接口连续失败，本轮提前收尾，延后的记录没有落行，下次同步自动重试。");
        }
        if (run.unavailable() > 0) {
            text.append("。UNAVAILABLE 表示 open-meteo 没有该时段数据（或记录缺坐标），重试无效。");
        }
        return text.toString();
    }

    /** 库里全部运动记录数（含步行等非跑步项目）；仓储不是 JDBC 版时返回 {@code null}。 */
    private Integer allWorkoutCount() {
        if (repository instanceof JdbcHealthDataRepository jdbcRepository) {
            return jdbcRepository.workoutCount();
        }
        return null;
    }

    /**
     * 持久化层状态：库里现有记录量、本次同步的增量统计。
     *
     * <p>只在仓储实现为 JDBC 版时给出明细，其它实现（含测试中的内存实现）返回 {@code null}，
     * 保证工具层不依赖具体仓储类型。
     *
     * @param health 华为导出的规模，由调用方预先取好（见 {@link #healthInfo()}）；{@code null} 时不加该块
     */
    private Map<String, Object> storageInfo(Map<String, Object> health) {
        if (!(repository instanceof JdbcHealthDataRepository jdbcRepository)) {
            return null;
        }
        RunRows.SyncResult sync = jdbcRepository.lastSync();
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", "sqlite");
        storage.put("dbPath", properties.dbPath());
        // 明确口径：这三个数只统计本次扫描到的 TCX 文件，华为导出的是 health 下的 scannedHuaweiFiles 等
        storage.put("scannedTcxFiles", sync.scanned());
        storage.put("importedTcxFiles", sync.imported());
        storage.put("unchangedTcxFiles", sync.unchanged());
        storage.put("syncedAt", formatDateTime(sync.syncedAt()));
        storage.put("missingSourceFiles", sync.missingSourceFiles());
        Map<String, Object> weather = weatherInfo();
        if (weather != null) {
            storage.put("weather", weather);
        }
        if (health != null) {
            storage.put("health", health);
        }
        return storage;
    }

    /** 华为导出的规模；仓储不是 JDBC 版时返回 {@code null}。 */
    private Map<String, Object> healthInfo() {
        if (!(repository instanceof JdbcHealthDataRepository jdbcRepository)) {
            return null;
        }
        return healthInfo(jdbcRepository);
    }

    /**
     * 华为导出的规模：华为来源的运动（全部 / 跑步 / 其它）、健康指标、分钟活动、Excel 明细各有多少行。
     *
     * <p>这些表不参与跑步分析，但用户需要能确认「全量导出确实进库了」，
     * 所以挂在存储状态里，而不是单独开一个工具。{@code scannedHuaweiFiles} 等三个计数
     * 是本次同步扫描到的华为导出文件（JSON / xls / xlsx）数，与 {@code storage.scannedTcxFiles} 不是同一个口径。
     */
    private Map<String, Object> healthInfo(JdbcHealthDataRepository repository) {
        try {
            HuaweiExportImporter.StorageStats stats = repository.healthStats();
            HuaweiExportImporter.Result sync = repository.lastHuaweiSync();
            Map<String, Object> health = new LinkedHashMap<>();
            // 这三个数是「华为来源」的运动数，与被 JSON 接管的 TCX 记录一起构成顶层的 allWorkoutCount
            health.put("huaweiWorkouts", stats.workouts());
            health.put("runningWorkouts", stats.runningWorkouts());
            health.put("otherWorkouts", stats.otherWorkouts());
            health.put("healthMetrics", stats.healthMetrics());
            health.put("activityMinutes", stats.activityMinutes());
            health.put("metricTypes", stats.metricTypes());
            health.put("bodyMeasurements", stats.bodyMeasurements());
            health.put("sleepSessions", stats.sleepSessions());
            health.put("activityDays", stats.activityDays());
            health.put("vitalDays", stats.vitalDays());
            health.put("sampleSequences", stats.sampleSequences());
            health.put("sleepReports", stats.sleepReports());
            health.put("ecgRecords", stats.ecgRecords());
            health.put("excelSheets", stats.excelSheets());
            health.put("excelRows", stats.excelRows());
            health.put("trackStreams", stats.trackStreams());
            health.put("firstMetricDate", stats.firstMetricDate());
            health.put("lastMetricDate", stats.lastMetricDate());
            health.put("scannedHuaweiFiles", sync.scanned());
            health.put("importedHuaweiFiles", sync.imported());
            health.put("unchangedHuaweiFiles", sync.unchanged());
            health.put("mergedDuplicateWorkouts", sync.mergedDuplicates());
            return health;
        } catch (RuntimeException e) {
            log.warn("读取华为导出统计失败", e);
            return Map.of("error", String.valueOf(e.getMessage()));
        }
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

    /**
     * 厂商官方分段的返回结构。
     *
     * <p>单独成块而不是混进 {@code splits}：两者口径不同（GPS 插值 vs 设备自算），
     * 混在一起会让调用方误以为是同一份数据。
     */
    private Map<String, Object> vendorMap(VendorSplits vendor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", vendor.source());
        result.put("splitMeters", vendor.splitMeters());
        result.put("totalKm", vendor.totalKm());
        result.put("avgPaceSecondsPerKm", vendor.avgPaceSecondsPerKm());
        result.put("avgPaceText", vendor.avgPaceText());
        result.put("splits", vendor.splits());
        return result;
    }

    /**
     * 数据质量体检：每一项都是一份 id 列表，空列表代表该项通过。
     *
     * <p>{@code clean} 对所有检查项取合取，只要有一项非空就为 false——它是「能不能放心用」的
     * 单一开关，必须和下面的明细严格一致。之前漏掉了 {@code duplicateActivityIds} 与
     * {@code missingAltitudeRunIds}，会输出 clean=true 而这两项非空的矛盾结果，
     * 调用方只信 clean 的话就漏检了。
     */
    private Map<String, Object> quality(RunDataset dataset) {
        List<RunActivity> activities = dataset.activities();
        List<String> zeroDistance = ids(activities, run -> run.distanceMeters() <= 0);
        List<String> missingPace = ids(activities, run -> run.avgPaceSecondsPerKm() == null);
        List<String> noGps = ids(activities, run -> run.startLatitude() == null);
        List<String> missingAltitude = ids(activities, run -> run.minAltitudeMeters() == null);
        Map<String, Long> activityIdCounts = activities.stream()
                .map(RunActivity::activityId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, LinkedHashMap::new, Collectors.counting()));
        List<String> duplicateActivityIds = activityIdCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> outliers = paceOutliers(activities);
        List<String> duplicateTimestamps = duplicateTimestampRunIds(activities);

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("warningCount", dataset.warnings().size());
        quality.put("warningCategories", warningCategories(dataset.warnings()));
        quality.put("zeroDistanceRunIds", zeroDistance);
        quality.put("missingPaceRunIds", missingPace);
        quality.put("noGpsRunIds", noGps);
        quality.put("missingAltitudeRunIds", missingAltitude);
        quality.put("duplicateActivityIds", duplicateActivityIds);
        quality.put("paceOutliers", outliers);
        quality.put("duplicateTimestampRunIds", duplicateTimestamps);
        boolean clean = dataset.warnings().isEmpty()
                && zeroDistance.isEmpty()
                && missingPace.isEmpty()
                && noGps.isEmpty()
                && missingAltitude.isEmpty()
                && duplicateActivityIds.isEmpty()
                && outliers.isEmpty()
                && duplicateTimestamps.isEmpty();
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
        // 华为导出独有：TCX 来源的记录这里全是 null
        row.put("avgHeartRate", run.avgHeartRate());
        row.put("maxHeartRate", run.maxHeartRate());
        row.put("minHeartRate", run.minHeartRate());
        row.put("avgCadence", run.avgCadence());
        row.put("totalSteps", run.totalSteps());
        row.put("totalCaloriesKcal", run.totalCaloriesKcal());
        row.put("recordId", run.recordId());
        row.put("sportTypeCode", run.sportTypeCode());
        row.put("subType", run.subType());
        // 原文约 2.5 KB 且是一整个转义字符串，塞进详情只会挤占上下文；
        // 这里只给「有没有、多大、去哪儿取」，内容交给 get_run_summary_data
        Map<String, Object> summaryData = new LinkedHashMap<>();
        String text = run.summaryData();
        boolean available = text != null && !text.isBlank();
        summaryData.put("available", available);
        if (available) {
            summaryData.put("bytes", text.length());
            summaryData.put("tool", "get_run_summary_data");
        } else {
            summaryData.put("reason", "TCX 来源没有这个字段，或该次运动未带 summaryData");
        }
        row.put("summaryData", summaryData);
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
        // 华为导出独有：TCX 来源的记录这里全是 null。summaryData 约 2.5 KB，只放在详情里
        row.put("avgHeartRate", run.avgHeartRate());
        row.put("maxHeartRate", run.maxHeartRate());
        row.put("minHeartRate", run.minHeartRate());
        row.put("avgCadence", run.avgCadence());
        row.put("totalSteps", run.totalSteps());
        row.put("totalCaloriesKcal", run.totalCaloriesKcal());
        row.put("recordId", run.recordId());
        row.put("sportTypeCode", run.sportTypeCode());
        row.put("subType", run.subType());
        return row;
    }

    private Map<String, Object> toRoutePage(Page<TrackPoint> page) {
        return toRoutePage(page, true);
    }

    private Map<String, Object> toRoutePage(Page<TrackPoint> page, boolean includeSensors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("offset", page.offset());
        result.put("limit", page.limit());
        result.put("hasMore", page.hasMore());
        result.put("points", page.items().stream().map(point -> toRoutePoint(point, includeSensors)).toList());
        return result;
    }

    /**
     * GeoJSON 的传感器数据：与坐标等长、同序的平行数组。
     *
     * <p>{@code alignedWith} 是给消费方的显式提示——LineString 的坐标里塞不下第四个维度，
     * 只能靠下标对应，说清楚比让人猜更省事。
     */
    private Map<String, Object> sensorSeries(List<TrackPoint> points) {
        Map<String, Object> sensors = new LinkedHashMap<>();
        sensors.put("alignedWith", "geometry.coordinates");
        sensors.put("heartRate", points.stream().map(TrackPoint::heartRate).toList());
        sensors.put("cadence", points.stream().map(TrackPoint::cadence).toList());
        sensors.put("distanceMeters", points.stream()
                .map(point -> point.distanceMeters() == null
                        ? null
                        : PaceFormat.round(point.distanceMeters(), 2))
                .toList());
        return sensors;
    }

    /**
     * 轨迹点的对外结构：海拔与累计距离按米保留 2 位小数，压掉导出文件里的浮点噪声；经纬度保持原精度。
     *
     * <p>心率/步频/累计距离恒为键存在的形式（无数据给 {@code null}），这样消费方不用先判断键在不在；
     * {@code includeSensors=false} 时才整体省略这三个键。
     */
    private Map<String, Object> toRoutePoint(TrackPoint point, boolean includeSensors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", formatDateTime(point.timestamp()));
        row.put("latitude", point.latitude());
        row.put("longitude", point.longitude());
        row.put("altitudeMeters", point.altitudeMeters() == null
                ? null
                : PaceFormat.round(point.altitudeMeters(), 2));
        if (includeSensors) {
            row.put("heartRate", point.heartRate());
            row.put("cadence", point.cadence());
            row.put("distanceMeters", point.distanceMeters() == null
                    ? null
                    : PaceFormat.round(point.distanceMeters(), 2));
        }
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

    /**
     * {@code list_runs} 的入参校验。这几项过去都会被静默吞掉：{@code sortBy} 认不出就按 date 排，
     * {@code weekday} 越界等于不按星期过滤（返回整段数据，看起来像「那天确实有跑」），
     * {@code min > max} 则直接抛 {@link IllegalArgumentException} 让整个调用失败而不是给状态。
     *
     * @return 非 {@code null} 表示校验失败，直接把它当作工具返回值
     */
    private Map<String, Object> validateListArgs(String sortBy, Integer weekday,
                                                 Double minDistanceKm, Double maxDistanceKm,
                                                 Double minPaceSecondsPerKm, Double maxPaceSecondsPerKm) {
        if (sortBy != null && !sortBy.isBlank()
                && !SORT_VALUES.contains(sortBy.trim().toLowerCase(Locale.ROOT))) {
            return invalidArgument("sortBy", sortBy,
                    "无法识别的排序方式 \"" + sortBy + "\"，支持 date / distance / duration / pace / climb；"
                            + "不传按 date（新的在前）", SORT_VALUES);
        }
        if (weekday != null && (weekday < 1 || weekday > 7)) {
            return invalidArgument("weekday", weekday,
                    "weekday 取值范围 1~7（1=周一，7=周日），收到 " + weekday + "；不传表示不按星期过滤",
                    List.of("1", "2", "3", "4", "5", "6", "7"));
        }
        if (minDistanceKm != null && maxDistanceKm != null && minDistanceKm > maxDistanceKm) {
            return invalidArgument("minDistanceKm", minDistanceKm,
                    "最小距离 " + formatKm(minDistanceKm) + " 公里大于最大距离 " + formatKm(maxDistanceKm)
                            + " 公里，过滤结果必然为空；两个边界都包含在内", null);
        }
        if (minPaceSecondsPerKm != null && maxPaceSecondsPerKm != null
                && minPaceSecondsPerKm > maxPaceSecondsPerKm) {
            return invalidArgument("minPaceSecondsPerKm", minPaceSecondsPerKm,
                    "配速下限 " + minPaceSecondsPerKm + " 秒/公里 大于上限 " + maxPaceSecondsPerKm
                            + " 秒/公里（数值越小越快，下限应不大于上限）", null);
        }
        return null;
    }

    /**
     * 分组粒度归一化：不传按 {@code day}；给了但认不出来返回 {@code null}，
     * 由调用方转成 {@code INVALID_ARGUMENT}——静默按 day 分组会让「按周看」的请求拿到日粒度数据。
     */
    /**
     * 参数非法的统一返回：数据本身没问题，要改的是调用参数，所以不能复用 {@code NO_DATA}。
     * 带上 {@code param} / {@code value}，调用方不必猜是哪个参数出的问题。
     *
     * @param allowedValues 枚举型参数的允许值，没有就传 {@code null}
     */
    private Map<String, Object> invalidArgument(String param, Object value, String message,
                                                List<String> allowedValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INVALID_ARGUMENT");
        result.put("param", param);
        result.put("value", jsonSafeValue(value));
        result.put("message", message);
        if (allowedValues != null && !allowedValues.isEmpty()) {
            result.put("allowedValues", allowedValues);
        }
        return result;
    }

    /** {@code NaN} / {@code Infinity} 不是合法 JSON 数字，回显时降级成字符串，避免返回体被序列化成非法 JSON。 */
    private static Object jsonSafeValue(Object value) {
        if (value instanceof Double d && !Double.isFinite(d)) {
            return String.valueOf(d);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SportHealthTools::jsonSafeValue).toList();
        }
        return value;
    }

    /** {@code splitKm} 的校验口径：不传走默认值；非正数、NaN、无穷与小于 0.1 公里都属于非法。 */
    private static boolean validSplitKm(Double splitKm) {
        return splitKm == null || (Double.isFinite(splitKm) && splitKm >= MIN_SPLIT_KM);
    }

    private Map<String, Object> invalidSplitKm(Double splitKm) {
        return invalidArgument("splitKm", splitKm,
                "splitKm 必须是 ≥ " + formatKm(MIN_SPLIT_KM) + " 公里的有限数值，收到 " + splitKm
                        + "；不传则默认按 " + formatKm(DEFAULT_SPLIT_KM) + " 公里分段", null);
    }

    /**
     * {@code distancesKm} 里非法项的可读形式：{@code null}、非正数、NaN、Infinity。
     *
     * @return 空列表表示全部合法
     */
    private static List<String> invalidDistances(List<Double> distancesKm) {
        return distancesKm.stream()
                .filter(km -> km == null || !Double.isFinite(km) || km <= 0)
                .map(String::valueOf)
                .toList();
    }

    private static String normalizeGroup(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "day";
        }
        String key = groupBy.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "day", "week", "month" -> key;
            default -> null;
        };
    }

    /**
     * 类别归一化：不传按 {@code all}；给了但认不出来返回 {@code null}，由调用方转成
     * {@code INVALID_ARGUMENT}——静默变成 {@code all} 会返回一堆没被请求的字段。
     *
     * <p>别名比较宽松：大小写、下划线、连字符都不敏感（{@code Best-5K} 等价于 {@code best5k}），
     * 但「认不出」和「没传」必须分开。
     */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "all";
        }
        String key = category.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (key) {
            case "1k", "best1k", "best1km", "best1000" -> "best1k";
            case "3k", "best3k", "best3km", "best3000" -> "best3k";
            case "5k", "best5k", "best5km", "best5000" -> "best5k";
            case "10k", "best10k", "best10km", "best10000" -> "best10k";
            case "15k", "best15k", "best15km" -> "best15k";
            case "half", "halfmarathon", "hm", "21k", "21.1k", "besthalf", "半马", "半程马拉松" -> "bestHalf";
            case "marathon", "full", "fm", "42k", "42.2k", "bestmarathon", "全马", "马拉松" -> "bestMarathon";
            default -> Set.of("distance", "pace", "duration", "climb", "all").contains(key) ? key : null;
        };
    }

    /** 距离类别 → 目标距离（米）；非距离类别返回 null。 */
    private static Double categoryTargetMeters(String key) {
        return switch (key) {
            case "best1k" -> 1000d;
            case "best3k" -> 3000d;
            case "best5k" -> 5000d;
            case "best10k" -> 10000d;
            case "best15k" -> 15000d;
            case "bestHalf" -> 21097.5d;
            case "bestMarathon" -> 42195d;
            default -> null;
        };
    }

    /**
     * 本次要算最佳努力的目标距离（公里）。
     *
     * <p>优先级：显式 {@code distancesKm} &gt; 距离类别 &gt; {@code all} 的默认 {@code [1,5]}。
     * 只查 distance/pace/duration/climb 时不附带最佳努力，避免白算一遍轨迹。
     * {@code category} 是「只看某一项」的语法糖，和 {@code distancesKm} 同时给时以距离列表为准。
     *
     * @return 空列表表示本次不需要计算最佳努力
     */
    private static List<Double> effortTargetsKm(String key, List<Double> distancesKm) {
        List<Double> explicit = distancesKm == null ? List.of() : distancesKm.stream()
                .filter(Objects::nonNull)
                .filter(km -> km > 0)
                .distinct()
                .sorted()
                .toList();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if ("all".equals(key)) {
            return List.of(1d, 5d);
        }
        Double target = categoryTargetMeters(key);
        return target == null ? List.of() : List.of(target / 1000d);
    }

    /**
     * 目标距离在返回体里的键名：1/3/5/10/15 公里用 {@code best5k} 这种写法，
     * 半马全马用固定名，其余距离按公里数保留两位小数（{@code 2.5 → best2.5k}）。
     */
    static String effortLabel(double targetMeters) {
        double km = targetMeters / 1000d;
        if (Math.abs(km - 21.0975) < 0.01) {
            return "bestHalf";
        }
        if (Math.abs(km - 42.195) < 0.01) {
            return "bestMarathon";
        }
        if (Math.abs(km - Math.rint(km)) < 1e-6) {
            return "best" + (long) Math.rint(km) + "k";
        }
        return "best" + new BigDecimal(String.valueOf(PaceFormat.round(km, 2)))
                .stripTrailingZeros()
                .toPlainString() + "k";
    }

    /** 公里数的展示写法：整公里不带小数，其余保留必要位数。 */
    private static String formatKm(double km) {
        if (Math.abs(km - Math.rint(km)) < 1e-6) {
            return String.valueOf((long) Math.rint(km));
        }
        return new BigDecimal(String.valueOf(PaceFormat.round(km, 2)))
                .stripTrailingZeros()
                .toPlainString();
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
