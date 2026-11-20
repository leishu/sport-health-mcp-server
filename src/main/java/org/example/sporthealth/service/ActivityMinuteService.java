package org.example.sporthealth.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.ActivityMinute;
import org.example.sporthealth.domain.ActivityMinuteBlock;
import org.example.sporthealth.domain.ActivityMinuteDay;
import org.example.sporthealth.domain.ActivityMinuteType;
import org.example.sporthealth.repository.ActivityMinuteStore;
import org.example.sporthealth.support.DateRange;
import org.springframework.stereotype.Service;

/**
 * 分钟级活动的读法：某一天每一分钟在做什么、连续成了哪几段。
 *
 * <p>和 {@link DailyActivityService} 的分工：那边回答「这周每天走了多少、够不够」，
 * 只读派生表 {@code daily_activity}；这边回答「某一天 06:00 到 07:00 在干什么」，
 * 读的是原始分钟表。两者的日合计<b>同源</b>（都来自 {@link ActivityMinuteStore#MERGED_MINUTE_SQL}
 * 去重后的分钟），所以同一天步数必须相等 —— 对不上说明派生表没重建。
 *
 * <p>三件容易读错的事，都在这里一次性说清：
 * <ul>
 *   <li><b>一分钟一行不是天然成立的</b>：2 208 个分钟存了两条，全部是手表 + 手机同时记；
 *       去重只保留步数大的那条，所以 {@code rawRows} 会大于 {@code records}；</li>
 *   <li><b>站立事件不是「站了几分钟」</b>：{@code sportType=13} 是整点触发的标记，
 *       {@code duration=0}、步数 0，默认不混进分钟列表；</li>
 *   <li><b>连续段允许 2 分钟空档</b>：手表会漏记个别分钟，严格相邻会把一次跑步拆成好几段。</li>
 * </ul>
 */
@Service
@Slf4j
public class ActivityMinuteService {

    /** 类型过滤：默认只看活动分钟，站立事件单独看。 */
    public static final List<String> FILTERS = List.of("activity", "all", "walk", "run", "stand");

    /** 连续段允许的最大空档（分钟）：再长就认为是两段不同的活动。 */
    static final int BLOCK_GAP_MINUTES = 2;

    /** 走路在两个设备上有两个码：3 号设备报 2，另两台报 5。 */
    private static final int WALK_CODE_ALT = 2;

    private static final int WALK_CODE = 5;

    private static final int RUN_CODE = 4;

    private static final int STAND_CODE = 13;

    /** 类型输出顺序：活动类型在前（按分钟数降序排），站立永远垫底。 */
    private static final List<String> TYPE_ORDER = List.of("run", "walk", "other", "stand");

    private static final Map<DayOfWeek, String> WEEKDAYS = Map.of(
            DayOfWeek.MONDAY, "周一",
            DayOfWeek.TUESDAY, "周二",
            DayOfWeek.WEDNESDAY, "周三",
            DayOfWeek.THURSDAY, "周四",
            DayOfWeek.FRIDAY, "周五",
            DayOfWeek.SATURDAY, "周六",
            DayOfWeek.SUNDAY, "周日");

    /** 一天的完整读数：概览 + 分类型汇总 + 连续段 + 逐分钟明细。 */
    public record DayMinutes(
            ActivityMinuteDay summary,
            List<ActivityMinuteType> types,
            List<ActivityMinuteBlock> blocks,
            List<ActivityMinute> minutes,
            List<String> notes) {
    }

    private final ActivityMinuteStore store;
    private final Clock clock;

    public ActivityMinuteService(ActivityMinuteStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 默认日期 = 数据最新一天，不是「今天」：库通常落后于当天（导出那天往往只有几条记录），
     * 用今天会返回一个空的「还没过完的日子」。
     */
    public LocalDate defaultDate() {
        return store.coveredRange().map(DateRange::to).orElse(LocalDate.now(clock));
    }

    /** 全库最早与最晚的活动日，用来解释「为什么这天没有数据」。 */
    public Optional<DateRange> coveredRange() {
        return store.coveredRange();
    }

    /**
     * 读一天的分钟明细。
     *
     * @param date      日期
     * @param filter    类型过滤，取值见 {@link #FILTERS}；{@code activity}（默认）不含站立事件
     */
    public DayMinutes day(LocalDate date, String filter) {
        String mode = (filter == null || filter.isBlank()) ? "activity" : filter;
        List<ActivityMinuteStore.MinuteRow> rows = store.minutes(date);

        ActivityMinuteDay summary = summary(date, rows);
        List<ActivityMinuteType> types = types(rows);

        List<ActivityMinuteStore.MinuteRow> filtered = rows.stream().filter(row -> matches(row, mode)).toList();
        List<ActivityMinute> minutes = filtered.stream().map(ActivityMinuteService::toMinute).toList();
        List<ActivityMinuteBlock> blocks = buildBlocks(filtered);

        return new DayMinutes(summary, types, blocks, minutes,
                notes(summary, blocks, minutes, filter));
    }

    // ---------------------------------------------------------------- 概览

    private static ActivityMinuteDay summary(LocalDate date, List<ActivityMinuteStore.MinuteRow> rows) {
        if (rows.isEmpty()) {
            return new ActivityMinuteDay(date, weekday(date), 0, 0, 0, 0, 0, 0, 0, 0, null, null, true);
        }
        int rawRows = rows.stream().mapToInt(ActivityMinuteStore.MinuteRow::sourceRows).sum();
        int records = rows.size();
        List<ActivityMinuteStore.MinuteRow> activity = rows.stream().filter(r -> !r.stand()).toList();
        int steps = rows.stream().mapToInt(ActivityMinuteStore.MinuteRow::steps).sum();
        double distanceKm = rows.stream().mapToDouble(ActivityMinuteStore.MinuteRow::distanceMeters).sum() / 1000d;
        double calories = rows.stream().mapToDouble(ActivityMinuteStore.MinuteRow::caloriesKcal).sum();
        int activeMinutes = rows.stream()
                .filter(ActivityMinuteStore.MinuteRow::intensity)
                .mapToInt(r -> Math.max(1, r.durationMinutes()))
                .sum();
        int standEvents = (int) rows.stream().filter(ActivityMinuteStore.MinuteRow::stand).count();
        String first = activity.isEmpty() ? null : time(activity.get(0).start());
        String last = activity.isEmpty() ? null : time(activity.get(activity.size() - 1).end());
        return new ActivityMinuteDay(date, weekday(date), rawRows, records, activity.size(),
                steps, round(distanceKm, 2), round(calories, 1), activeMinutes, standEvents,
                first, last, records < DailyActivityService.SPARSE_MINUTE_ROWS);
    }

    /** 按类型汇总：站立垫底，其余按分钟数从多到少。 */
    private static List<ActivityMinuteType> types(List<ActivityMinuteStore.MinuteRow> rows) {
        Map<String, List<ActivityMinuteStore.MinuteRow>> buckets = new LinkedHashMap<>();
        for (ActivityMinuteStore.MinuteRow row : rows) {
            buckets.computeIfAbsent(typeKey(row.sportType()), k -> new ArrayList<>()).add(row);
        }
        List<ActivityMinuteType> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, List<ActivityMinuteStore.MinuteRow>> entry : buckets.entrySet()) {
            List<ActivityMinuteStore.MinuteRow> bucket = entry.getValue();
            TreeSet<Integer> codes = new TreeSet<>();
            bucket.forEach(r -> codes.add(r.sportType()));
            out.add(new ActivityMinuteType(
                    entry.getKey(),
                    label(entry.getKey()),
                    List.copyOf(codes),
                    bucket.size(),
                    bucket.stream().mapToInt(ActivityMinuteStore.MinuteRow::steps).sum(),
                    round(bucket.stream().mapToDouble(ActivityMinuteStore.MinuteRow::distanceMeters).sum() / 1000d, 2),
                    round(bucket.stream().mapToDouble(ActivityMinuteStore.MinuteRow::caloriesKcal).sum(), 1),
                    time(bucket.get(0).start()),
                    time(bucket.get(bucket.size() - 1).end())));
        }
        out.sort((a, b) -> {
            int byOrder = Integer.compare(TYPE_ORDER.indexOf(a.type()), TYPE_ORDER.indexOf(b.type()));
            return byOrder != 0 ? byOrder : Integer.compare(b.minutes(), a.minutes());
        });
        return out;
    }

    // ---------------------------------------------------------------- 明细

    /**
     * 把逐分钟拼成连续段：同类型、且与上一段尾巴相差不超过 {@link #BLOCK_GAP_MINUTES} 分钟。
     *
     * <p>站立事件（整点、孤立）不参与，否则每小时的「站立」都会变成一段。
     */
    private static List<ActivityMinuteBlock> buildBlocks(List<ActivityMinuteStore.MinuteRow> rows) {
        List<ActivityMinuteBlock> blocks = new ArrayList<>();
        String currentType = null;
        LocalDateTime blockStart = null;
        LocalDateTime blockEnd = null;
        int minutes = 0;
        int steps = 0;
        double meters = 0;
        double kcal = 0;
        for (ActivityMinuteStore.MinuteRow row : rows) {
            if (row.stand()) {
                continue;
            }
            String type = typeKey(row.sportType());
            boolean sameBlock = currentType != null && currentType.equals(type)
                    && !row.start().isBefore(blockEnd)
                    && !row.start().isAfter(blockEnd.plusMinutes(BLOCK_GAP_MINUTES));
            if (!sameBlock) {
                if (currentType != null) {
                    blocks.add(new ActivityMinuteBlock(currentType, label(currentType),
                            time(blockStart), time(blockEnd), minutes, steps,
                            round(meters / 1000d, 2), round(kcal, 1)));
                }
                currentType = type;
                blockStart = row.start();
                minutes = 0;
                steps = 0;
                meters = 0;
                kcal = 0;
            }
            blockEnd = row.end();
            minutes++;
            steps += row.steps();
            meters += row.distanceMeters();
            kcal += row.caloriesKcal();
        }
        if (currentType != null) {
            blocks.add(new ActivityMinuteBlock(currentType, label(currentType),
                    time(blockStart), time(blockEnd), minutes, steps,
                    round(meters / 1000d, 2), round(kcal, 1)));
        }
        return blocks;
    }

    private static boolean matches(ActivityMinuteStore.MinuteRow row, String filter) {
        String type = typeKey(row.sportType());
        return switch (filter) {
            case "all" -> true;
            case "walk" -> "walk".equals(type);
            case "run" -> "run".equals(type);
            case "stand" -> "stand".equals(type);
            default -> !row.stand();
        };
    }

    private static ActivityMinute toMinute(ActivityMinuteStore.MinuteRow row) {
        String type = typeKey(row.sportType());
        return new ActivityMinute(
                time(row.start()),
                time(row.end()),
                row.sportType(),
                type,
                label(type),
                row.steps(),
                round(row.distanceMeters(), 1),
                round(row.caloriesKcal(), 2),
                row.intensity(),
                row.stand(),
                row.deviceCode(),
                row.sourceRows());
    }

    // ---------------------------------------------------------------- 文案

    private static List<String> notes(ActivityMinuteDay summary, List<ActivityMinuteBlock> blocks,
                                      List<ActivityMinute> minutes, String filter) {
        List<String> notes = new ArrayList<>();
        if (summary.records() == 0) {
            notes.add("这一天没有任何分钟记录。");
            return notes;
        }
        if (summary.sparse()) {
            notes.add("这一天只有 " + summary.records() + " 条分钟记录（正常一天 120~500 条），"
                    + "多半是没戴表或导出那天还没过完，别把 " + summary.steps() + " 步当成整天的活动量。");
        }
        int duplicated = summary.rawRows() - summary.records();
        if (duplicated > 0) {
            notes.add("当天 " + summary.rawRows() + " 条原始记录里有 " + duplicated
                    + " 条是同一分钟被两台设备各记一条（手表 + 手机），已按「步数大的那条」合并成 "
                    + summary.records() + " 条；不合并会多算步数、距离和热量。");
        }
        if (!"all".equals(filter) && !"stand".equals(filter) && summary.standEvents() > 0) {
            notes.add("另有 " + summary.standEvents() + " 条站立事件（sportType=13，整点触发、0 步、duration=0）"
                    + "没算进分钟列表；要看它们用 type=stand。站立事件是「起身次数」，不是站立时长。");
        }
        if ("stand".equals(filter) && !minutes.isEmpty()) {
            notes.add("站立事件是整点标记，彼此相隔一小时，不是连续的「站立 N 分钟」；"
                    + "当天的合计见 summary.standEvents。");
        }
        if (!blocks.isEmpty()) {
            ActivityMinuteBlock longest = blocks.stream()
                    .max((a, b) -> Integer.compare(a.minutes(), b.minutes())).orElseThrow();
            notes.add("连续段按「同类型、前后相差 ≤" + BLOCK_GAP_MINUTES + " 分钟」拼接，空档不计入 minutes："
                    + "当天共 " + blocks.size() + " 段，最长的一段是 " + longest.from() + "~" + longest.to()
                    + " " + longest.label() + " " + longest.minutes() + " 分钟 " + longest.steps() + " 步。");
        }
        if (minutes.isEmpty() && !"stand".equals(filter)) {
            notes.add("该天没有匹配类型=" + filter + " 的记录；可用 type=all 看全部。");
        }
        notes.add("口径：start_time 是本地墙上时间（+08:00），一条记录正常覆盖 1 分钟；"
                + "steps / distanceKm / caloriesKcal 与 get_daily_activity 同源（同一份去重后的分钟数据），"
                + "所以 summary.steps 应当与那天的 days[].steps 相等；caloriesKcal 是活动热量，不含基础代谢。");
        return notes;
    }

    // ---------------------------------------------------------------- 内部

    /** 原始 {@code sport_type} → 类型键。2 与 5 都是走路，见 {@link ActivityMinuteStore}。 */
    static String typeKey(int sportType) {
        return switch (sportType) {
            case RUN_CODE -> "run";
            case WALK_CODE, WALK_CODE_ALT -> "walk";
            case STAND_CODE -> "stand";
            default -> "other";
        };
    }

    private static String label(String type) {
        return switch (type) {
            case "run" -> "跑步";
            case "walk" -> "走路";
            case "stand" -> "站立事件";
            default -> "其它";
        };
    }

    private static String weekday(LocalDate date) {
        return WEEKDAYS.getOrDefault(date.getDayOfWeek(), date.getDayOfWeek().name());
    }

    /** {@code HH:mm}；不输出秒，分钟级数据的秒恒为 0。 */
    private static String time(LocalDateTime value) {
        return String.format(Locale.ROOT, "%02d:%02d", value.getHour(), value.getMinute());
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
