package org.example.sporthealth.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.OfficialRaceComparison;
import org.example.sporthealth.domain.RacePrediction;
import org.example.sporthealth.domain.RunLevel;
import org.example.sporthealth.domain.RunLevelHistory;
import org.example.sporthealth.repository.RunLevelStore;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.example.sporthealth.support.RaceDistance;
import org.springframework.stereotype.Service;

/**
 * 华为官方跑力：读取、趋势与「官方 vs Riegel」对照。
 *
 * <p>官方值是华为基于个人跑力给出的目标成绩，和 {@link RunAnalyticsService#predictRace} 的
 * Riegel 外推是两套口径。本服务只负责把两组数字并列，并逐行标注来源与差值，
 * 不判断谁更准——官方值通常更贴近本人水平，Riegel 则只反映区间内的实际跑步表现。
 */
@Service
@Slf4j
public class RunLevelService {

    /** 官方数据来源标识，写进每个返回块。 */
    public static final String SOURCE = "HUAWEI_OFFICIAL";

    private static final int DEFAULT_HISTORY_DAYS = 90;
    /** 上限放宽到 10 年，实际受库里数据量约束（目前 368 天）。 */
    private static final int MAX_HISTORY_DAYS = 3650;
    /** 官方与 Riegel 相差不超过这个秒数时，视为「基本一致」而不是谁快谁慢。 */
    private static final double CONSISTENT_SECONDS = 30d;

    private final RunLevelStore store;

    public RunLevelService(RunLevelStore store) {
        this.store = store;
    }

    /** 最新一天官方跑力。 */
    public Optional<RunLevel> latest() {
        return store.latest();
    }

    /** 指定日期或之前最近一天的官方跑力。 */
    public Optional<RunLevel> latestOnOrBefore(LocalDate day) {
        return store.latestOnOrBefore(day);
    }

    /**
     * 跑力趋势。窗口以 {@code anchor}（默认最新一天）为结束日，向前推 {@code days} 天。
     *
     * @param anchor 结束日期，为空时取最新快照
     * @param days   窗口天数，默认 90，上限 3650
     */
    public RunLevelHistory history(LocalDate anchor, Integer days) {
        LocalDate to = anchor != null ? anchor : store.latest().map(RunLevel::date).orElse(null);
        if (to == null) {
            return null;
        }
        int window = days == null || days <= 0 ? DEFAULT_HISTORY_DAYS : Math.min(days, MAX_HISTORY_DAYS);
        LocalDate from = to.minusDays(window - 1L);
        List<RunLevelHistory.Point> points = store.between(from, to).stream()
                .map(level -> new RunLevelHistory.Point(level.date(), level.score()))
                .toList();
        Double change = null;
        if (points.size() >= 2) {
            Double first = points.get(0).score();
            Double last = points.get(points.size() - 1).score();
            if (first != null && last != null) {
                change = PaceFormat.round(last - first, 2);
            }
        }
        return new RunLevelHistory(from, to, points.size(), points, change,
                historyText(points, change, from, to));
    }

    /**
     * 官方成绩与 Riegel 预测的并列对照。
     *
     * <p>官方快照取「区间结束日或之前最近一天」：{@code run_level} 是每日快照，
     * 与区间本身没有严格对应关系，取最近一天比取区间中位数更贴近当前水平。
     * 快照不在区间内时把 {@code withinRange=false}、{@code staleDays} 一并返回，便于调用方提示时效。
     *
     * @param prediction Riegel 预测结果
     * @param range      本次查询区间
     * @return 没有官方数据时返回 {@code null}
     */
    public OfficialRaceComparison compare(RacePrediction prediction, DateRange range) {
        if (prediction == null || range == null) {
            return null;
        }
        Optional<RunLevel> snapshot = store.latestOnOrBefore(range.to());
        if (snapshot.isEmpty()) {
            return null;
        }
        RunLevel level = snapshot.get();
        List<OfficialRaceComparison.Row> rows = new ArrayList<>();
        List<Double> unavailable = new ArrayList<>();
        for (RacePrediction.Prediction item : prediction.predictions()) {
            RunLevel.OfficialTime official = level.officialTimes().stream()
                    .filter(time -> Math.abs(time.distanceMeters() - item.targetMeters()) < 1d)
                    .findFirst()
                    .orElse(null);
            if (official == null) {
                unavailable.add(PaceFormat.round(item.targetMeters() / 1000d, 3));
                continue;
            }
            double delta = PaceFormat.round(official.seconds() - item.predictedSeconds(), 1);
            rows.add(new OfficialRaceComparison.Row(
                    PaceFormat.round(item.targetMeters() / 1000d, 3),
                    item.targetMeters(),
                    official.seconds(),
                    official.timeText(),
                    official.paceSecondsPerKm(),
                    official.paceText(),
                    SOURCE,
                    item.predictedSeconds(),
                    item.predictedText(),
                    item.source(),
                    delta,
                    deltaText(delta, item.source())));
        }
        long staleDays = Math.max(0L, range.to().toEpochDay() - level.date().toEpochDay());
        boolean withinRange = range.contains(level.date());
        return new OfficialRaceComparison(level.date(), level.score(), SOURCE, withinRange, staleDays,
                List.copyOf(rows), List.copyOf(unavailable),
                comparisonText(level, rows, unavailable, withinRange));
    }

    /** 一天的跑力摘要：跑力值、五档配速、官方成绩。 */
    public String describe(RunLevel level) {
        if (level == null) {
            return "没有可用的华为官方跑力数据";
        }
        StringBuilder text = new StringBuilder("华为官方跑力 ").append(scoreText(level.score()))
                .append("（").append(level.date()).append("）");
        if (!level.zones().isEmpty()) {
            text.append("：")
                    .append(level.zones().stream()
                            .map(zone -> zone.code() + " " + zone.paceText())
                            .collect(Collectors.joining("、")))
                    .append("；官方成绩 ");
        } else {
            text.append("：官方成绩 ");
        }
        if (level.officialTimes().isEmpty()) {
            text.append("缺失");
        } else {
            text.append(level.officialTimes().stream()
                    .map(time -> RaceDistance.label(time.distanceMeters()) + " " + time.timeText())
                    .collect(Collectors.joining("、")));
        }
        return text.toString();
    }

    private static String comparisonText(RunLevel level, List<OfficialRaceComparison.Row> rows,
                                         List<Double> unavailable, boolean withinRange) {
        StringBuilder text = new StringBuilder("华为官方跑力 ")
                .append(scoreText(level.score()))
                .append("（").append(level.date());
        if (!withinRange) {
            text.append("，不在所选区间内，取区间结束前最近一天");
        }
        text.append("）");
        if (rows.isEmpty()) {
            text.append("没有所选距离的官方档位；官方只给 1/3/5/10 公里、半马、全马");
        } else {
            text.append("给出 ")
                    .append(rows.stream()
                            .map(row -> RaceDistance.label(row.targetMeters()) + " " + row.officialText())
                            .collect(Collectors.joining("、")))
                    .append("；");
            OfficialRaceComparison.Row longest = rows.get(rows.size() - 1);
            text.append(RaceDistance.label(longest.targetMeters())).append(longest.deltaText());
        }
        if (!unavailable.isEmpty()) {
            text.append("；")
                    .append(unavailable.stream()
                            .map(km -> RaceDistance.label(km * 1000d))
                            .collect(Collectors.joining("、")))
                    .append("官方无对应档位");
        }
        return text.toString();
    }

    private static String historyText(List<RunLevelHistory.Point> points, Double change,
                                      LocalDate from, LocalDate to) {
        if (points.isEmpty()) {
            return "区间 " + from + " ~ " + to + " 内没有官方跑力数据";
        }
        RunLevelHistory.Point first = points.get(0);
        RunLevelHistory.Point last = points.get(points.size() - 1);
        if (points.size() == 1 || change == null) {
            return "只有 " + first.date() + " 一天的官方跑力 " + scoreText(first.score());
        }
        if (change == 0d) {
            return "跑力持平在 " + scoreText(last.score()) + "（" + first.date() + " ~ " + last.date() + "）";
        }
        String direction = change > 0 ? "升" : "降";
        return "跑力从 " + scoreText(first.score()) + "（" + first.date() + "）"
                + direction + "到 " + scoreText(last.score()) + "（" + last.date() + "），"
                + (change > 0 ? "+" : "") + PaceFormat.round(change, 1);
    }

    private static String scoreText(Double score) {
        return score == null ? "未知" : String.valueOf(PaceFormat.round(score, 1));
    }

    /**
     * 差值文案。基线要说清是「Riegel 外推」还是「区间内实测」——
     * 目标距离本身有实测成绩时，拿官方值去跟实测比更有意义，写成「比 Riegel 快」会说错对象。
     */
    private static String deltaText(double delta, String baselineSource) {
        boolean actual = "ACTUAL".equals(baselineSource);
        String baseline = actual ? "区间实测" : "Riegel";
        // 中文基线不加空格，Riegel 是拉丁词，前后要留空格
        String withPrefix = actual ? "与" + baseline : "与 " + baseline + " ";
        String comparePrefix = actual ? "比" + baseline : "比 " + baseline + " ";
        double abs = Math.abs(delta);
        if (abs < 1d) {
            return withPrefix + "一致";
        }
        if (abs <= CONSISTENT_SECONDS) {
            return withPrefix + "基本一致（差 " + deltaAmount(abs) + "）";
        }
        return comparePrefix + (delta < 0 ? "快 " : "慢 ") + deltaAmount(abs);
    }

    /** 差值用「33 秒 / 1 分 23 秒」而不是 {@code PaceFormat.duration} 的「0 分 33 秒」。 */
    private static String deltaAmount(double seconds) {
        long total = Math.round(seconds);
        return total < 60 ? total + " 秒" : PaceFormat.duration(seconds);
    }
}
