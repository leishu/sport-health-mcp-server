package org.example.sporthealth.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.example.sporthealth.domain.BodyMeasurement;
import org.example.sporthealth.domain.BodyMetricTrend;
import org.example.sporthealth.domain.BodyMonthSummary;
import org.example.sporthealth.domain.BodyProfile;
import org.example.sporthealth.repository.BodyCompositionStore;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.stereotype.Service;

/**
 * 体脂秤数据的筛选与统计。
 *
 * <p>这个服务存在的全部理由就是**别把几个人混成一个人**：
 * {@code body_measurement} 里 308 条分属 7 套配置，全表体重极差 55.85~85.35 kg
 * 是四五个人拼出来的，任何「平均体重」「体重变化」都必须先落到某一套配置上。
 *
 * <p>因此工具层强制「不传 gender + heightCm 就只能看配置汇总」，
 * 只有两个都给了才进明细模式 —— 静默挑一个配置（哪怕条数最多）都是危险的猜测。
 */
@Service
public class BodyCompositionService {

    /** 明细默认跨度：90 天。体重变化按月看比较稳，30 天常常只有五六条。 */
    private static final int DEFAULT_DAYS = 90;

    /** 月度概览最多保留的月份数，更早的截掉并在 notes 说明。 */
    private static final int MAX_MONTHS = 36;

    private final BodyCompositionStore store;

    public BodyCompositionService(BodyCompositionStore store) {
        this.store = store;
    }

    /** 全库首次与最后一次称重的日期；配置发现要看完整历史，不能只窗口 90 天。 */
    public DateRange coveredRange() {
        return store.coveredRange().orElse(null);
    }

    /** 全库最后一次称重的日期，给配置汇总模式的默认区间定锚点。 */
    public LocalDate latestDate() {
        return store.latestDate().orElse(null);
    }

    /** 某套配置最后一次称重的日期；这套配置在库里没记录时返回 {@code null}。 */
    public LocalDate latestDateFor(String gender, Integer heightCm) {
        if (gender == null || heightCm == null) {
            return null;
        }
        return store.latestDateFor(genderCode(gender), heightCm).orElse(null);
    }

    /**
     * 区间内的配置汇总，已按传入的 gender / heightCm 过滤。
     *
     * @param gender   {@code male} / {@code female}，{@code null} 表示不按性别过滤
     * @param heightCm 身高，{@code null} 表示不按身高过滤
     */
    public List<BodyProfile> profiles(DateRange range, String gender, Integer heightCm) {
        return store.profiles(range).stream()
                .filter(profile -> gender == null || gender.equals(profile.gender()))
                .filter(profile -> heightCm == null || heightCm.equals(profile.heightCm()))
                .toList();
    }

    /**
     * 区间解析。
     *
     * <p>默认锚点是**这套配置自己的最后一次称重**，不是「今天」也不是「全库最新日」：
     * 186cm/男 从 2026-11 起就称得稀了，而 168cm/女 一直称到今天 ——
     * 用全库最新日当锚点，查 186cm/男 的默认区间会常常是空的。
     *
     * @param anchor 锚点日期（该配置最后一条）；为 {@code null} 时退化成「今天」
     */
    public DateRange resolveRange(String startDate, String endDate, LocalDate anchor) {
        LocalDate lastDay = anchor == null ? LocalDate.now() : anchor;
        LocalDate to = isPresent(endDate) ? DateParser.parseDate(endDate.trim()) : lastDay;
        LocalDate from = isPresent(startDate) ? DateParser.parseDate(startDate.trim())
                : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 晚于结束日期 " + to);
        }
        return new DateRange(from, to);
    }

    /** 区间内的测量明细，按时间升序；这套配置在区间里没有记录时返回空列表。 */
    public List<BodyMeasurement> measurements(BodyProfile profile, DateRange range) {
        if (profile == null || profile.heightCm() == null || "unknown".equals(profile.gender())) {
            return List.of();
        }
        return store.measurements(range, genderCode(profile.gender()), profile.heightCm());
    }

    /**
     * 六个指标的区间变化。
     *
     * <p>{@code change} 用「最后一条 − 第一条」，不是「最后一条 − 均值」：
     * 体重的日常波动有 ±1 kg，拿均值当参照会把一次称重的水分放大成趋势。
     * 同一天多次测量按时间顺序全部参与，不做同日去重（去重会让「那天称了两次」的数据消失）。
     */
    public Map<String, BodyMetricTrend> trends(List<BodyMeasurement> measurements) {
        Map<String, BodyMetricTrend> out = new LinkedHashMap<>();
        put(out, "weight", "kg", measurements, BodyMeasurement::weightKg);
        put(out, "bmi", "", measurements, BodyMeasurement::bmi);
        put(out, "bodyFatPercent", "%", measurements, BodyMeasurement::bodyFatPercent);
        put(out, "muscleMassKg", "kg", measurements, BodyMeasurement::muscleMassKg);
        put(out, "visceralFatLevel", "level", measurements, BodyMeasurement::visceralFatLevel);
        put(out, "bodyScore", "score", measurements, BodyMeasurement::bodyScore);
        return out;
    }

    /**
     * 按月聚合，回答「这几个月体重怎么走的」。
     *
     * <p>月均用全部测量点平均（同一天两次就算两个点）：这里只描述「秤上看到的数字」，
     * 不做同日取最值之类的清洗，否则月均会和明细对不上。
     */
    public List<BodyMonthSummary> byMonth(List<BodyMeasurement> measurements) {
        Map<String, List<BodyMeasurement>> grouped = new LinkedHashMap<>();
        for (BodyMeasurement measurement : measurements) {
            grouped.computeIfAbsent(measurement.date().substring(0, 7), key -> new ArrayList<>())
                    .add(measurement);
        }
        List<BodyMonthSummary> out = new ArrayList<>(grouped.size());
        grouped.forEach((month, items) -> {
            double weightSum = 0;
            double weightMin = Double.MAX_VALUE;
            double weightMax = -Double.MAX_VALUE;
            double fatSum = 0;
            int fatCount = 0;
            List<String> days = new ArrayList<>();
            for (BodyMeasurement item : items) {
                weightSum += item.weightKg();
                weightMin = Math.min(weightMin, item.weightKg());
                weightMax = Math.max(weightMax, item.weightKg());
                if (item.bodyFatPercent() != null) {
                    fatSum += item.bodyFatPercent();
                    fatCount++;
                }
                if (!days.contains(item.date())) {
                    days.add(item.date());
                }
            }
            out.add(new BodyMonthSummary(
                    month,
                    items.size(),
                    days.size(),
                    PaceFormat.round(weightSum / items.size(), 2),
                    PaceFormat.round(weightMin, 2),
                    PaceFormat.round(weightMax, 2),
                    fatCount == 0 ? null : PaceFormat.round(fatSum / fatCount, 2)));
        });
        out.sort(Comparator.comparing(BodyMonthSummary::month));
        return out.size() <= MAX_MONTHS ? out : out.subList(out.size() - MAX_MONTHS, out.size());
    }

    /**
     * 明细模式的口径提示。
     *
     * @param siblings 区间内除当前配置外的其它配置，用来提示「别把这当一个人的数据」
     */
    public List<String> notes(BodyProfile profile, DateRange range, List<BodyMeasurement> measurements,
                              List<BodyProfile> siblings) {
        List<String> notes = new ArrayList<>();
        notes.add("本次统计只包含「" + profile.label() + "」这一套配置（"
                + measurements.size() + " 条），不是整张表的聚合。");

        List<BodyProfile> others = siblings.stream()
                .filter(other -> !sameProfile(other, profile))
                .toList();
        if (!others.isEmpty()) {
            String names = others.stream()
                    .map(other -> other.label() + " " + other.measurements() + " 条")
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            notes.add("同一区间里还有 " + others.size() + " 套配置（" + names
                    + "），已排除；否则体重会被跨人平均，极差会假得离谱。");
        }

        long withComposition = measurements.stream()
                .filter(m -> m.bodyFatPercent() != null)
                .count();
        if (withComposition == 0) {
            notes.add("这 " + measurements.size() + " 条都只称了体重，没有体成分数据："
                    + "bmi / 体脂率 / 肌肉量等字段全是 null，不是 0。");
        } else if (withComposition < measurements.size()) {
            notes.add(measurements.size() + " 条里只有 " + withComposition
                    + " 条带体成分，其余只有体重；trends 里各指标的 count 是「有值的条数」，"
                    + "所以会比明细条数少。");
        }

        long fatKgMissing = measurements.stream()
                .filter(m -> m.bodyFatPercent() != null && m.bodyFatKg() == null)
                .count();
        if (fatKgMissing > 0) {
            notes.add("有 " + fatKgMissing + " 条记录了体脂率却没带 bodyFatKg（体脂公斤数），"
                    + "这里保持 null 不做换算；需要的话可用 体重 × 体脂率 自己补。");
        }

        Map<String, Integer> perDay = new LinkedHashMap<>();
        for (BodyMeasurement measurement : measurements) {
            perDay.merge(measurement.date(), 1, Integer::sum);
        }
        long multiDay = perDay.values().stream().filter(count -> count > 1).count();
        if (multiDay > 0) {
            notes.add("有 " + multiDay + " 天称了不止一次（同一天多次会逐条保留，"
                    + "byMonth 的均值把每次称重都算进去）。");
        }

        if (measurements.size() >= 2) {
            LocalDate first = LocalDate.parse(measurements.get(0).date());
            LocalDate last = LocalDate.parse(measurements.get(measurements.size() - 1).date());
            long span = last.toEpochDay() - first.toEpochDay();
            long measuredDays = perDay.size();
            if (span > 0) {
                notes.add("区间内首次到末次相隔 " + span + " 天、实际有测量 " + measuredDays
                        + " 天（平均 " + PaceFormat.round(span / (double) measuredDays, 1)
                        + " 天一条），体重曲线是插值出来的，不是每天的真实值。");
            }
        }
        return notes;
    }

    /** 配置汇总模式的提示：必须把「还得再加参数」说清楚。 */
    public List<String> profileNotes(List<BodyProfile> profiles, DateRange range,
                                     String gender, Integer heightCm) {
        List<String> notes = new ArrayList<>();
        if (profiles.isEmpty()) {
            return notes;
        }
        int total = profiles.stream().mapToInt(BodyProfile::measurements).sum();
        notes.add("区间 " + range.text() + " 内共 " + profiles.size() + " 套配置、" + total
                + " 条记录，它们属于不同的人，**不能合并统计**。");
        notes.add("请再加 gender + heightCm 过滤：gender 取 male / female，heightCm 取整数身高，"
                + "两个都给才会进入明细模式。");
        BodyProfile biggest = profiles.get(0);
        notes.add("条数最多的是「" + biggest.label() + "」（" + biggest.measurements()
                + " 条，" + biggest.firstDate() + " ~ " + biggest.lastDate()
                + " 体重 " + biggest.weightMin() + "~" + biggest.weightMax() + " kg），"
                + "但这只是条数排序，不代表就是本人 —— 同一台秤上的每个人都会留下记录。");
        List<BodyProfile> withComposition = profiles.stream()
                .filter(profile -> profile.bodyComposition() > 0)
                .toList();
        if (!withComposition.isEmpty()) {
            String names = withComposition.stream()
                    .map(profile -> profile.label() + " " + profile.bodyComposition() + " 条")
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            notes.add("带体成分（体脂率/肌肉量等）的配置有 " + withComposition.size()
                    + " 套：" + names + "。只称体重、测不出体成分的配置，通常是没在秤上建个人档案的那个用户。");
        }
        if (gender == null) {
            notes.add("当前没传 gender：性别未知的那几条（库里 gender 为 NULL）无法归到任何人身上，"
                    + "它们单独成组。");
        }
        if (heightCm == null) {
            notes.add("当前没传 heightCm：身高缺失的记录同样单独成组。");
        }
        // 同一身高既有「有性别」又有「性别未知」的组：很可能是同一个人的部分记录没带性别字段。
        // 但两个组也可能是不同的人（比如一家人都 168cm），所以只提示、不合并。
        Map<Integer, List<BodyProfile>> byHeight = new LinkedHashMap<>();
        for (BodyProfile profile : profiles) {
            if (profile.heightCm() != null) {
                byHeight.computeIfAbsent(profile.heightCm(), key -> new ArrayList<>()).add(profile);
            }
        }
        byHeight.forEach((height, group) -> {
            List<BodyProfile> known = group.stream().filter(p -> !"unknown".equals(p.gender())).toList();
            List<BodyProfile> unknown = group.stream().filter(p -> "unknown".equals(p.gender())).toList();
            if (!known.isEmpty() && !unknown.isEmpty()) {
                String detail = group.stream()
                        .map(p -> p.label() + " " + p.weightMin() + "~" + p.weightMax() + " kg")
                        .reduce((a, b) -> a + "；" + b).orElse("");
                notes.add(height + "cm 下同时存在 " + (known.size() + unknown.size())
                        + " 套配置（" + detail + "），体重区间有重叠时很可能是同一个人的部分记录没带性别字段。"
                        + "工具不会自动合并 —— 如果确认是同一人，两组要分别查、或按日期区间拼起来看。");
            }
        });
        return notes;
    }

    private static void put(Map<String, BodyMetricTrend> out, String key, String unit,
                            List<BodyMeasurement> measurements,
                            Function<BodyMeasurement, Double> getter) {
        List<Double> values = measurements.stream().map(getter).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return;
        }
        double first = values.get(0);
        double last = values.get(values.size() - 1);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(first);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(first);
        double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(first);
        double change = last - first;
        out.put(key, new BodyMetricTrend(
                unit,
                PaceFormat.round(first, 2),
                PaceFormat.round(last, 2),
                PaceFormat.round(change, 2),
                first == 0 ? null : PaceFormat.round(change * 100d / first, 1),
                PaceFormat.round(min, 2),
                PaceFormat.round(max, 2),
                PaceFormat.round(average, 2),
                values.size()));
    }

    private static boolean sameProfile(BodyProfile a, BodyProfile b) {
        return a.gender().equals(b.gender())
                && java.util.Objects.equals(a.heightCm(), b.heightCm());
    }

    /** 库里 1 = 男、0 = 女（实测 186cm/男 的记录全是 1）。 */
    private static int genderCode(String gender) {
        return "male".equals(gender) ? 1 : 0;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
