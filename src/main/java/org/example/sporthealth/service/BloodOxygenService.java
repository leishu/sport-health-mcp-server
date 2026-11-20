package org.example.sporthealth.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.BloodOxygen;
import org.example.sporthealth.domain.Spo2Day;
import org.example.sporthealth.domain.Spo2LowEvent;
import org.example.sporthealth.domain.VitalsSample;
import org.example.sporthealth.repository.VitalsMetricStore;
import org.example.sporthealth.repository.VitalsMetricStore.TimedSample;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.example.sporthealth.support.PaceFormat;
import org.springframework.stereotype.Service;

/**
 * 血氧饱和度（SpO₂）的测量记录。
 *
 * <p><b>这个指标在库里只有 12 天</b>：2026-06-12~17 与 2026-08-03~08 两段，558 条，
 * 之后设备再没上报过。所以这里刻意<b>不做趋势判定</b> —— 两段之间隔着一个半月，
 * 拟合出任何斜率都只是「设备有没有开连续血氧」的记录，不是身体变化。
 *
 * <p>另一点容易误读：<b>「没有血氧记录」和「血氧正常」是两件事</b>。8 月 8 日之后一条都没有，
 * 不能说那段时间血氧没问题；同理，区间内一条低氧提醒都没有，也不代表测得都正常
 * —— 可能整段就没测。
 */
@Service
public class BloodOxygenService {

    private static final String METRIC = "BLOOD_OXYGEN_SATURATION";

    /** 默认跨度 30 天：血氧是按「开了连续监测」成段出现的，窗口太短容易整段落空。 */
    private static final int DEFAULT_DAYS = 30;

    /** 明细上限，约 200 条。全库才 558 条，正常查询到不了这个数。 */
    private static final int MAX_RECORDS = 200;

    /** 临床常用的「偏低」参考线，只用来数样本，不是诊断标准。 */
    private static final int LOW_REFERENCE = 95;

    private final VitalsMetricStore store;

    public BloodOxygenService(VitalsMetricStore store) {
        this.store = store;
    }

    public LocalDate latestDate() {
        return store.latestDate(METRIC).orElse(null);
    }

    public DateRange coveredRange() {
        return store.span(METRIC).orElse(null);
    }

    /**
     * 解析查询区间。
     *
     * <p>锚点是「血氧数据自己的最后一天」（2026-08-08），不是今天 —— 这个指标 8 月就停更了，
     * 用今天当锚点的话默认区间会整段落空，用户会以为数据坏了。
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

    /** 区间内的血氧记录与低氧提醒；区间里一条记录都没有时返回 {@code null}。 */
    public BloodOxygen overview(DateRange range) {
        if (range == null) {
            return null;
        }
        List<TimedSample> records = store.spo2(range);
        if (records.isEmpty()) {
            return null;
        }
        List<Spo2LowEvent> events = store.lowOxygenEvents(range);
        List<Spo2Day> daily = daily(records);

        boolean truncated = records.size() > MAX_RECORDS;
        List<TimedSample> kept = truncated ? records.subList(0, MAX_RECORDS) : records;
        List<VitalsSample> detail = new ArrayList<>(kept.size());
        for (TimedSample sample : kept) {
            detail.add(new VitalsSample(sample.clock(), sample.value()));
        }

        return new BloodOxygen(
                range,
                daily.size(),
                records.size(),
                daily.get(0).date(),
                daily.get(daily.size() - 1).date(),
                daily,
                detail,
                truncated,
                events,
                notes(range, daily, records.size(), events, truncated));
    }

    /**
     * 按 {@link TimedSample#at()} 的日期归组。
     *
     * <p>不能像压力那样单日查询 —— 血氧一个区间跨十几天，样本必须靠完整时刻才知道属于哪一天。
     */
    private static List<Spo2Day> daily(List<TimedSample> records) {
        Map<LocalDate, List<Double>> grouped = new LinkedHashMap<>();
        for (TimedSample sample : records) {
            grouped.computeIfAbsent(sample.at().toLocalDate(), key -> new ArrayList<>())
                    .add(sample.value());
        }
        List<Spo2Day> out = new ArrayList<>(grouped.size());
        grouped.forEach((date, values) -> out.add(new Spo2Day(
                date,
                values.size(),
                (int) Math.round(values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN)),
                PaceFormat.round(values.stream()
                        .mapToDouble(Double::doubleValue).average().orElse(Double.NaN), 1),
                (int) Math.round(values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN)),
                (int) values.stream().filter(value -> value < LOW_REFERENCE).count())));
        out.sort(Comparator.comparing(Spo2Day::date));
        return out;
    }

    private List<String> notes(DateRange range, List<Spo2Day> daily, int total,
                               List<Spo2LowEvent> events, boolean truncated) {
        List<String> notes = new ArrayList<>();
        int lowest = daily.stream().mapToInt(Spo2Day::min).min().orElse(Integer.MAX_VALUE);
        int below = daily.stream().mapToInt(Spo2Day::below95).sum();

        notes.add("血氧不是持续监测项：区间内只有 " + daily.size() + " 天有记录、共 " + total
                + " 条，只在设备开启连续血氧的时段才上报。没有记录不等于血氧正常，"
                + "这个区别必须在结论里说清楚。");
        if (daily.size() < range.dayCount()) {
            notes.add("查询区间 " + range.dayCount() + " 天，其中只有 " + daily.size()
                    + " 天有记录，其余日子设备没上报。");
        }
        if (below > 0) {
            notes.add("有 " + below + " 条样本低于 " + LOW_REFERENCE + "%（最低 " + lowest
                    + "%）。单点低值常见于手冷、佩戴松、夜间翻身压到手腕，"
                    + "要看是否连续多分钟一起低。");
        }
        if (!events.isEmpty()) {
            notes.add("设备下发了 " + events.size() + " 次血氧偏低提醒（阈值见 threshold，"
                    + "实测为 90%）。这是设备自己的判定，和「低于 95% 的样本数」不是一个口径。");
        }
        if (truncated) {
            notes.add("明细只返回前 " + MAX_RECORDS + " 条，其余看 daily 汇总；"
                    + "需要完整明细请缩短区间。");
        }
        return notes;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
