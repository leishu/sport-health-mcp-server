package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.BloodOxygen;
import org.example.sporthealth.domain.HrvNight;
import org.example.sporthealth.domain.HrvTrend;
import org.example.sporthealth.domain.StressDay;
import org.example.sporthealth.service.BloodOxygenService;
import org.example.sporthealth.service.HrvTrendService;
import org.example.sporthealth.service.StressSeriesService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * HRV / 压力 / 血氧三个「稀疏单点」指标的 MCP 工具。
 *
 * <p>和 {@link VitalsHealthTools}、{@link HeartRateHealthTools} 分开，理由跟前两个一样：
 * 这三个指标的数据源、采样节奏、能回答的问题都不一样，合进一个类只会让参数校验和
 * 口径提示互相干扰。
 *
 * <p>三者分工是刻意错开的：
 * <ul>
 *   <li>{@code get_hrv_trend} 做<b>多日趋势</b>（HRV 有 152 天，够拟合）；</li>
 *   <li>{@code get_stress_series} 做<b>单日曲线</b>（压力一天 30 来个点，适合看当天分布）；</li>
 *   <li>{@code get_blood_oxygen} 做<b>记录与覆盖</b>（只有 12 天，做不了趋势，只给明细与低氧事件）。</li>
 * </ul>
 * 硬把血氧也做趋势，等于把「设备开没开监测」拟合成生理变化。
 */
@Component
@Slf4j
public class WellnessTools {

    /** 逐夜明细上限：再多的夜数返回体就没法看了，只给周期聚合。 */
    private static final int MAX_NIGHTS = 120;

    private final HrvTrendService hrv;
    private final StressSeriesService stress;
    private final BloodOxygenService spo2;

    public WellnessTools(HrvTrendService hrv, StressSeriesService stress, BloodOxygenService spo2) {
        this.hrv = hrv;
        this.stress = stress;
        this.spo2 = spo2;
    }

    /**
     * 睡眠 HRV 的长期趋势。
     *
     * <p>刻意不暴露「全天 HRV」这种口径：那个数取决于当天白天测了几条，
     * 拿来拟合斜率量到的是采样方式，不是恢复能力（详见 {@link HrvTrendService}）。
     */
    @McpTool(name = "get_hrv_trend",
            description = "查询心率变异性（HRV / RMSSD，毫秒）的长期趋势，按周或月聚合。"
                    + "用于回答“最近 HRV 是在变好还是变差”“这几个月恢复能力怎么样”"
                    + "“HRV 和训练量有没有关系”“哪几晚 HRV 特别低”。"
                    + "口径是睡眠 HRV：每晚取睡眠会话窗口内所有样本的均值，按起床日归属，"
                    + "不是日历日全天均值 —— 日间均值比夜间高约 20 ms，用全天均值算趋势量到的是"
                    + "“那天白天测得多不多”。返回每个周期的均值/最低/最高、趋势方向与拟合优度 r2、"
                    + "首末变化，以及逐夜明细（可用 includeNights 关掉）。"
                    + "direction 是数值涨跌，assessment 才是好坏 —— HRV 上升是 improving，"
                    + "和静息心率那种“降了才好”的指标相反。"
                    + "只想看某一天的值用 get_daily_vitals；那里的 hrv 是全天均值，与本工具口径不同，"
                    + "数值对不上是正常的。")
    public Map<String, Object> getHrvTrend(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认是结束日期往前 90 天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取 HRV 数据最新一天", required = false) String endDate,
            @McpToolParam(description = "聚合粒度：week（默认，周一到周日）或 month（自然月）", required = false) String period,
            @McpToolParam(description = "是否返回逐夜的 HRV 明细，默认 true；只看周期均值就传 false", required = false) Boolean includeNights) {
        String mode = HrvTrendService.normalizePeriod(period);
        if (mode == null) {
            return invalidArgument("period", period,
                    "无法识别的粒度 \"" + period + "\"，支持 week / month；不传按 week",
                    hrv.periods());
        }

        DateRange range;
        try {
            range = hrv.resolveRange(startDate, endDate, hrv.latestDate());
        } catch (RuntimeException e) {
            return dateError(startDate, endDate);
        }

        HrvTrend trend = hrv.trend(range, mode);
        List<HrvNight> nights = Boolean.FALSE.equals(includeNights) ? List.of() : hrv.nights(range);
        if (trend.nights() == 0) {
            return noData("hrv", range, "区间内没有落在睡眠时段的 HRV 采样。", hrv.coveredRange(),
                    "HRV 从 2026-06-12 才有数据，更早的日期只能看 get_daily_vitals 的静息心率。");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("range", range.text());
        body.put("period", mode);
        body.put("unit", trend.unit());
        body.put("basis", trend.basis());
        body.put("nights", trend.nights());
        body.put("average", trend.average());
        body.put("min", trend.min());
        body.put("max", trend.max());
        body.put("change", trend.change());
        body.put("changePercent", trend.changePercent());
        body.put("slopePerPeriod", trend.slopePerPeriod());
        body.put("r2", trend.r2());
        body.put("direction", trend.direction());
        body.put("assessment", trend.assessment());
        body.put("periodsUsed", trend.periodsUsed());
        body.put("periodsExcluded", trend.periodsExcluded());
        body.put("excludedNights", trend.excludedNights());
        body.put("periodCount", trend.periods().size());
        body.put("periods", trend.periods());
        if (!nights.isEmpty()) {
            body.put("nightsTruncated", nights.size() > MAX_NIGHTS);
            body.put("nightCount", Math.min(nights.size(), MAX_NIGHTS));
            body.put("nightly", nights.size() > MAX_NIGHTS ? nights.subList(nights.size() - MAX_NIGHTS, nights.size()) : nights);
        }
        body.put("notes", trend.notes());
        return body;
    }

    /**
     * 单日压力曲线。
     *
     * <p>只做单日：多日的压力水平 {@code get_daily_vitals} 的 {@code stress} 已经给了，
     * 在这里再开一个多日口径，两边数字会打架。
     */
    @McpTool(name = "get_stress_series",
            description = "查询某一天的压力曲线（华为压力分 0~100）：全天样本数、最低/平均/最高、"
                    + "首次与末次测量时刻、采样间隔中位数，按时间分桶的曲线，各档位（放松/正常/中等/高压）"
                    + "各占多少样本，以及压力最高和最低的那一次测量。"
                    + "用于回答“那天压力曲线怎么样”“什么时候压力最高”“那天是不是一直在紧张”。"
                    + "三个必须知道的口径：① 压力是约 30 分钟一次的离散测量，不是连续曲线；"
                    + "② 最低分恒为 15 分，这是算法下限不是“压力为零”，全库 19.4% 的样本都落在这一档；"
                    + "③ 全库最大值只有 53 分，从没到过 60，“高压”档在这份数据里不会出现 —— "
                    + "没到高压不代表那天不累。多日压力水平请用 get_daily_vitals。")
    public Map<String, Object> getStressSeries(
            @McpToolParam(description = "日期，格式 yyyy-MM-dd，默认取压力数据最新一天", required = false) String date,
            @McpToolParam(description = "分桶粒度：30min 或 hour（默认）；数据本身约 30 分钟一个点，"
                    + "再细没有意义", required = false) String resolution) {
        Integer bucketMinutes = StressSeriesService.normalizeResolution(resolution);
        if (bucketMinutes == null) {
            return invalidArgument("resolution", resolution,
                    "无法识别的粒度 \"" + resolution + "\"，支持 30min / hour；不传按 hour",
                    stress.resolutions());
        }

        LocalDate day;
        if (isPresent(date)) {
            try {
                day = DateParser.parseDate(date.trim());
            } catch (RuntimeException e) {
                return invalidArgument("date", date, "无法解析日期 \"" + date + "\"，请使用 yyyy-MM-dd", null);
            }
        } else {
            day = stress.latestDate();
            if (day == null) {
                return noData("stress", null, "库里没有压力记录。", stress.coveredRange(), null);
            }
        }

        StressDay result = stress.day(day, bucketMinutes);
        if (result == null) {
            return noData("stress", new DateRange(day, day),
                    day + " 没有压力采样，可能是没戴表或设备没测。",
                    stress.coveredRange(), "可用 get_daily_vitals 看那几天的 stress 是否也缺失。");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("date", result.date().toString());
        body.put("bucketMinutes", bucketMinutes);
        body.put("samples", result.samples());
        body.put("min", result.min());
        body.put("average", result.average());
        body.put("max", result.max());
        body.put("level", result.level());
        body.put("firstSample", result.firstSample());
        body.put("lastSample", result.lastSample());
        body.put("medianGapMinutes", result.medianGapMinutes());
        body.put("peak", result.peak());
        body.put("calmest", result.calmest());
        body.put("levels", result.levels());
        body.put("bucketCount", result.buckets().size());
        body.put("buckets", result.buckets());
        body.put("notes", stress.notes(result));
        return body;
    }

    /**
     * 血氧记录。
     *
     * <p>不给趋势字段是刻意的：这个指标只有 12 天、分成相隔一个半月的两段，
     * 任何斜率都只是「设备有没有开连续血氧」。
     */
    @McpTool(name = "get_blood_oxygen",
            description = "查询血氧饱和度（SpO₂）的测量记录：按天汇总（样本数、最低/平均/最高、"
                    + "低于 95% 的样本数）、明细样本、以及设备自己下发的血氧偏低提醒（阈值 90%）。"
                    + "用于回答“晚上血氧掉到多少”“有没有低血氧事件”“那段时间血氧正常吗”。"
                    + "两个必须知道的口径：① 血氧不是持续监测项，全库只有 12 天有数据"
                    + "（2026-06-12~17 与 2026-08-03~08），之后设备再没上报 —— "
                    + "没有记录不等于血氧正常；② 本工具不给趋势，两段数据隔了一个半月，"
                    + "拟合出的变化只是设备开没开监测，不是身体变化。"
                    + "低氧判断请看 lowEvents（设备判定），不要用「最低值」自己下结论。")
    public Map<String, Object> getBloodOxygen(
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd，默认是结束日期往前 30 天", required = false) String startDate,
            @McpToolParam(description = "结束日期，格式 yyyy-MM-dd，默认取血氧数据最新一天（2026-08-08）", required = false) String endDate) {
        DateRange range;
        try {
            range = spo2.resolveRange(startDate, endDate, spo2.latestDate());
        } catch (RuntimeException e) {
            return dateError(startDate, endDate);
        }

        BloodOxygen result = spo2.overview(range);
        if (result == null) {
            return noData("bloodOxygen", range,
                    "区间内没有任何血氧记录。", spo2.coveredRange(),
                    "血氧只在 2026-06-12~17 与 2026-08-03~08 两段有数据，"
                            + "其他时间设备没有开启连续血氧监测。");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("range", result.range().text());
        body.put("days", result.days());
        body.put("records", result.records());
        body.put("firstDate", result.firstDate().toString());
        body.put("lastDate", result.lastDate().toString());
        body.put("daily", result.daily());
        if (!result.lowEvents().isEmpty()) {
            body.put("lowEventCount", result.lowEvents().size());
            body.put("lowEvents", result.lowEvents());
        } else {
            body.put("lowEventCount", 0);
        }
        body.put("sampleCount", result.samples().size());
        body.put("samplesTruncated", result.truncated());
        body.put("samples", result.samples());
        body.put("notes", result.notes());
        return body;
    }

    /** 两个日期参数都可能非空，报错时要指出到底哪个坏了。 */
    private Map<String, Object> dateError(String startDate, String endDate) {
        String bad = isPresent(startDate) ? startDate : endDate;
        String param = isPresent(startDate) ? "startDate" : "endDate";
        return invalidArgument(param, bad,
                "无法解析日期 \"" + bad + "\"，请使用 yyyy-MM-dd；若两端都合法，请检查开始日期是否晚于结束日期", null);
    }

    private Map<String, Object> invalidArgument(String param, Object value, String message,
                                                List<String> allowedValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INVALID_ARGUMENT");
        result.put("param", param);
        result.put("value", value);
        result.put("message", message);
        if (allowedValues != null && !allowedValues.isEmpty()) {
            result.put("allowedValues", allowedValues);
        }
        return result;
    }

    private Map<String, Object> noData(String metric, DateRange range, String message,
                                       DateRange covered, String hint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("metric", metric);
        if (range != null) {
            result.put("range", range.text());
        }
        result.put("message", message);
        if (covered != null) {
            result.put("coveredRange", covered.text());
        }
        List<String> hints = new ArrayList<>();
        if (hint != null) {
            hints.add(hint);
        }
        hints.add("可用 describe_data_source 查看各指标的记录区间与覆盖天数。");
        result.put("hint", String.join(" ", hints));
        return result;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
