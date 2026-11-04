package org.example.sporthealth.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.config.WeatherProperties;
import org.example.sporthealth.domain.RunWeather;
import org.example.sporthealth.domain.WeatherSnapshot;
import org.example.sporthealth.repository.RunWeatherStore;
import org.example.sporthealth.repository.RunWeatherStore.PendingRun;
import org.example.sporthealth.weather.OpenMeteoClient.Coordinate;
import org.example.sporthealth.weather.OpenMeteoClient.Endpoint;
import org.example.sporthealth.weather.OpenMeteoClient.OpenMeteoException;
import org.springframework.stereotype.Component;

/**
 * 把已有跑步记录的起跑 / 结束时刻天气补进 {@code run_weather}。
 *
 * <p><b>为什么放在导入事务之外</b>：连接池上限是 1，{@code TcxIncrementalImporter} 的写入整段在事务里。
 * HTTP 调用一旦放进事务，一次网络卡顿就会把唯一的连接占死，整个服务读写全部阻塞。
 * 所以这里在导入<b>结束之后</b>执行，每条记录单独一次短事务。
 *
 * <p><b>时效窗口分流</b>：open-meteo 的 archive 端点对最近几天的数据会返回 200 但全为 null
 * （实测当天请求得到 24 个 null），而 forecast 端点覆盖今天往前 92 天且数据完整。
 * 因此距今 {@code recentWindowDays} 内走 forecast，更早的走 archive。
 *
 * <p><b>状态机</b>：取到数据记 {@code OK}；接口正常但整个时段没有值记 {@code UNAVAILABLE}（终态，
 * 不再浪费配额）；网络、限流、5xx 记 {@code FAILED}，冷却 {@code retryFailedAfterDays} 天后重试。
 * 任何情况下天气失败<b>都不能影响跑步数据写入</b>，只在 warnings 里留一条汇总。
 *
 * <p><b>一天一次请求</b>：hourly 只能按天取，所以先按 UTC 日期把当天要用的坐标
 * （多条记录的起终点、跨天记录的另一半）汇总去重，一天只发一次请求，拿到响应后按坐标分发给
 * 各条记录。配额因此是「日期数」量级，不是「记录数」量级。
 *
 * <p><b>缓存键必须是「日期 + 坐标」</b>：起终点常在同一个 UTC 日期但相距几十公里（点对点跑步），
 * 只按日期复用会把终点的天气算成起点的。
 *
 * <p><b>连续失败短路</b>：端点黑洞（丢包不回 RST）时每次请求都要等满超时，连续
 * {@link #MAX_CONSECUTIVE_FAILURES} 次可重试失败后本轮直接收尾。放弃的记录不落行，
 * 下次同步会重新选中，不会因为一次网络故障把几十条记录一起打进 7 天冷却。
 *
 * <p><b>孤儿行清理</b>：{@code run_weather} 没有外键，记录被重导删除后天气行会留在表里，
 * 让覆盖率算出超过 100%。{@link RunWeatherStore#deleteOrphans()} 在每轮开始时打扫一次，
 * 不联网、不受开关影响。
 */
@Component
@Slf4j
public class WeatherBackfill {

    /**
     * 连续可重试失败达到这个次数就结束本轮。
     *
     * <p>取 3 是一个折中：偶发抖动不会让整轮停摆，真有网络问题时最多浪费三个超时。
     * 不做短路的话，每条记录都要等满 {@code timeout}，一次 {@code reload_data} 会被拖到
     * {@code maxRunsPerSync × timeout} 的量级，MCP 客户端大概率先超时。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final RunWeatherStore store;
    private final OpenMeteoClient client;
    private final WeatherProperties properties;

    public WeatherBackfill(RunWeatherStore store, OpenMeteoClient client, WeatherProperties properties) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    /**
     * 回填一轮。
     *
     * @param now 当前时刻，测试可注入固定时钟
     * @return 本轮统计；未启用时返回全 0
     */
    public Result run(Instant now) {
        // 孤儿行清理不联网，也不该被 enabled 开关挡住：记录已经被重导删除，行留着只会污染覆盖率
        int orphans = store.deleteOrphans();
        if (orphans > 0) {
            log.info("清理孤儿天气行 {} 条（对应跑步记录已不存在）", orphans);
        }
        if (!properties.enabled()) {
            return Result.skipped();
        }
        Instant retryBefore = now.minusSeconds(properties.retryFailedAfterDays() * 86_400L);
        List<PendingRun> pending = store.pending(retryBefore, properties.maxRunsPerSync());
        if (pending.isEmpty()) {
            return Result.skipped();
        }

        int noLocation = 0;
        List<Prepared> prepared = new ArrayList<>(pending.size());
        for (PendingRun run : pending) {
            RunWeather.Query start = startOf(run);
            RunWeather.Query end = endOf(run);
            if (start == null || end == null) {
                noLocation++;
                store.save(RunWeather.noLocation(run.runId(), now));
                continue;
            }
            prepared.add(new Prepared(run, start, end));
        }

        // 一次请求只能取一天的逐小时数据，所以按 UTC 日期汇总坐标：同一天的多条记录
        // （以及一条记录跨天时的另一半）共用一次响应。
        Map<LocalDate, Map<Coordinate, HourlySeries>> seriesByDate = new LinkedHashMap<>();
        Map<LocalDate, OpenMeteoException> failuresByDate = new LinkedHashMap<>();
        fetchDays(prepared, now, seriesByDate, failuresByDate);

        int ok = 0;
        int unavailable = 0;
        int failed = 0;
        int deferred = 0;
        for (Prepared item : prepared) {
            LocalDate startDate = OpenMeteoClient.utcDate(item.start().at());
            LocalDate endDate = OpenMeteoClient.utcDate(item.end().at());
            OpenMeteoException failure = failureFor(failuresByDate, startDate, endDate);
            if (failure != null) {
                // 这一天的请求真的发过并失败了，同一天的所有记录都被它覆盖，逐条落状态
                store.save(RunWeather.empty(item.run().runId(), item.start(), item.end(),
                        failure.getMessage(), failure.retryable(), now));
                if (failure.retryable()) {
                    failed++;
                } else {
                    unavailable++;
                }
                continue;
            }
            if (!seriesByDate.containsKey(startDate) || !seriesByDate.containsKey(endDate)) {
                // 被短路跳过的日期：不落行，下次同步重新选中
                deferred++;
                continue;
            }

            WeatherSnapshot startSnapshot = snapshotAt(seriesByDate.get(startDate), item.start());
            WeatherSnapshot endSnapshot = snapshotAt(seriesByDate.get(endDate), item.end());
            RunWeather weather = isMissing(startSnapshot) && isMissing(endSnapshot)
                    ? RunWeather.empty(item.run().runId(), item.start(), item.end(),
                            noDataMessage(startDate), false, now)
                    : RunWeather.ok(item.run().runId(), item.start(), item.end(),
                            startSnapshot, endSnapshot, now);
            store.save(weather);
            switch (weather.status()) {
                case OK -> ok++;
                case UNAVAILABLE -> unavailable++;
                case FAILED -> failed++;
            }
        }

        List<String> problems = distinctMessages(failuresByDate, false);
        List<String> retryableProblems = distinctMessages(failuresByDate, true);
        log.info("天气回填完成：候选 {} 条，成功 {}、无数据 {}、失败 {}、缺坐标 {}、延后 {}",
                pending.size(), ok, unavailable, failed, noLocation, deferred);
        return new Result(ok, unavailable, failed, noLocation, deferred, problems, retryableProblems);
    }

    /**
     * 起止日期都失败时优先返回可重试的那一个。
     *
     * <p>跨天记录会分别请求两天：若起跑日是 400（终态）而结束日是 503（可重试），
     * 按日期先后取第一个会把整条记录判成 {@code UNAVAILABLE}，从此不再重试。
     */
    private static OpenMeteoException failureFor(Map<LocalDate, OpenMeteoException> failures,
                                                 LocalDate startDate, LocalDate endDate) {
        OpenMeteoException start = failures.get(startDate);
        OpenMeteoException end = failures.get(endDate);
        if (start != null && start.retryable()) {
            return start;
        }
        if (end != null && end.retryable()) {
            return end;
        }
        return start != null ? start : end;
    }

    /**
     * 按 UTC 日期逐个取数，一天一次请求，坐标在请求内合并去重。
     *
     * <p>某个日期失败只影响这一天，错误记到 {@code failuresByDate}，由调用方逐条落状态。
     * 连续 {@link #MAX_CONSECUTIVE_FAILURES} 次可重试失败后直接返回，未取到的日期不会出现在任何
     * 一张 map 里，调用方据此判为「延后」。
     */
    private void fetchDays(List<Prepared> prepared, Instant now,
                           Map<LocalDate, Map<Coordinate, HourlySeries>> seriesByDate,
                           Map<LocalDate, OpenMeteoException> failuresByDate) {
        Map<LocalDate, LinkedHashSet<Coordinate>> wanted = new LinkedHashMap<>();
        for (Prepared item : prepared) {
            want(wanted, item.start());
            want(wanted, item.end());
        }

        int consecutiveFailures = 0;
        for (Map.Entry<LocalDate, LinkedHashSet<Coordinate>> entry : wanted.entrySet()) {
            LocalDate date = entry.getKey();
            List<Coordinate> coordinates = List.copyOf(entry.getValue());
            try {
                List<HourlySeries> series = client.fetchHourly(endpointFor(date, now), coordinates, date);
                seriesByDate.put(date, align(coordinates, series));
                consecutiveFailures = 0;
            } catch (OpenMeteoException e) {
                failuresByDate.put(date, e);
                log.warn("天气回填失败 {} {}", date, e.getMessage());
                if (!e.retryable()) {
                    // 语义性失败（日期超出数据源范围等）说明不了连通性，不计入短路
                    continue;
                }
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("天气回填连续失败 {} 次，本轮提前结束，剩余记录留待下次同步", consecutiveFailures);
                    return;
                }
            }
        }
    }

    private static void want(Map<LocalDate, LinkedHashSet<Coordinate>> wanted, RunWeather.Query query) {
        wanted.computeIfAbsent(OpenMeteoClient.utcDate(query.at()), ignored -> new LinkedHashSet<>())
                .add(query.coordinate());
    }

    /** 响应按入参顺序对齐到坐标：{@code fetchHourly} 保证返回列表与入参等长。 */
    private static Map<Coordinate, HourlySeries> align(List<Coordinate> coordinates, List<HourlySeries> series) {
        Map<Coordinate, HourlySeries> byCoordinate = new LinkedHashMap<>();
        for (int i = 0; i < coordinates.size(); i++) {
            HourlySeries value = i < series.size() ? series.get(i) : null;
            byCoordinate.put(coordinates.get(i), value == null ? HourlySeries.from(null) : value);
        }
        return byCoordinate;
    }

    /** 用该坐标自己的序列插值；坐标不在这一天的响应里时视为没有数据。 */
    private static WeatherSnapshot snapshotAt(Map<Coordinate, HourlySeries> seriesByCoordinate,
                                              RunWeather.Query query) {
        HourlySeries series = seriesByCoordinate.get(query.coordinate());
        return series == null ? null : WeatherInterpolator.at(series, query.at());
    }

    private static boolean isMissing(WeatherSnapshot snapshot) {
        return snapshot == null || snapshot.isEmpty();
    }

    private static String noDataMessage(LocalDate date) {
        return "open-meteo 在 " + date + " 没有返回可用的逐小时数据（该时段超出数据源覆盖范围）";
    }

    private static List<String> distinctMessages(Map<LocalDate, OpenMeteoException> failures,
                                                 boolean retryableOnly) {
        List<String> messages = new ArrayList<>();
        for (OpenMeteoException failure : failures.values()) {
            if (retryableOnly && !failure.retryable()) {
                continue;
            }
            String message = failure.getMessage();
            if (message != null && !messages.contains(message)) {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * 按日期选端点：{@code 今天 - 窗口 + 延迟} 之后走 forecast，更早的走 archive。
     *
     * <p>{@code archiveLagDays} 把 forecast 窗口最老的 {@code lag} 天交给 archive
     * （archive 对几十天前的历史是完整的），因此默认 92 / 5 下 forecast 实际只用最近 87 天。
     *
     * <p>日期基准必须来自 {@code now} 而不是 {@code LocalDate.now()}：
     * 同一轮里所有记录要共用同一个「今天」，否则跨零点运行时前后两条记录会落到不同端点。
     */
    Endpoint endpointFor(LocalDate date, Instant now) {
        LocalDate today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate forecastFloor = today.minusDays(properties.recentWindowDays())
                .plusDays(properties.archiveLagDays());
        return date.isBefore(forecastFloor) ? Endpoint.ARCHIVE : Endpoint.FORECAST;
    }

    /** 起跑时刻必须存在；坐标无效时返回 null，由调用方记 {@code noLocation}。 */
    static RunWeather.Query startOf(PendingRun run) {
        Instant at = parseInstant(run.startTimeUtc());
        if (at == null || run.startLatitude() == null || run.startLongitude() == null) {
            return null;
        }
        RunWeather.Query query = new RunWeather.Query(run.startLatitude(), run.startLongitude(),
                OpenMeteoClient.truncate(at));
        return query.valid() ? query : null;
    }

    /** 结束时刻 = 起跑 + 总时长；终点坐标缺失时退回起点（往返跑、环形跑都适用）。 */
    static RunWeather.Query endOf(PendingRun run) {
        Instant at = parseInstant(run.startTimeUtc());
        if (at == null) {
            return null;
        }
        Instant end = at.plusSeconds(Math.round(run.durationSeconds()));
        Double latitude = run.endLatitude();
        Double longitude = run.endLongitude();
        if (latitude == null || longitude == null) {
            latitude = run.startLatitude();
            longitude = run.startLongitude();
        }
        if (latitude == null || longitude == null) {
            return null;
        }
        RunWeather.Query query = new RunWeather.Query(latitude, longitude, OpenMeteoClient.truncate(end));
        return query.valid() ? query : null;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 起终点都有效的待补记录：坐标与时刻都已校验过，后续不再判空。 */
    private record Prepared(PendingRun run, RunWeather.Query start, RunWeather.Query end) {
    }

    /**
     * 一轮回填的统计。
     *
     * @param noLocation 记录本身缺坐标，直接标 {@code UNAVAILABLE}，没有发过请求
     * @param deferred   因连续失败提前收尾、本轮没有尝试的记录；不落行，下次同步继续
     * @param problems   失败的日期与原因，按消息去重
     * @param retryableProblems 其中可重试的失败原因；告警文案只能引用它，不能拿 400 的说明去说
     *                          「下次同步重试」
     */
    public record Result(
            int ok,
            int unavailable,
            int failed,
            int noLocation,
            int deferred,
            List<String> problems,
            List<String> retryableProblems) {

        public static Result skipped() {
            return new Result(0, 0, 0, 0, 0, List.of(), List.of());
        }

        /** 本轮真正发过请求并落了状态的记录数（不含缺坐标与延后的）。 */
        public int attempted() {
            return ok + unavailable + failed;
        }

        /** 本轮处理完的记录数：状态已落 + 缺坐标 + 延后。 */
        public int total() {
            return attempted() + noLocation + deferred;
        }
    }
}
