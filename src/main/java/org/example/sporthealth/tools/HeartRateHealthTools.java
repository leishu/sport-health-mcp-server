package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.HeartRateSeries;
import org.example.sporthealth.service.HeartRateSeriesService;
import org.example.sporthealth.support.DateParser;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 自动心率曲线的 MCP 工具。
 *
 * <p>和 {@link VitalsHealthTools} 分开：那个工具读的是「一天一行」的 {@code daily_vitals}
 * 快照，这个读的是 {@code health_metric} 里 34 万条分钟级采样 —— 数据源、返回形态、
 * 容易踩的坑都不一样。真要合并，参数校验和分桶逻辑会把那个类撑成第二个
 * {@code SportHealthTools}。
 *
 * <p>返回体约定与其它工具一致：{@code status} 区分 {@code OK} / {@code NO_DATA} /
 * {@code INVALID_ARGUMENT}；参数「不传 = 默认值，传了但值非法 = 报错」，不静默替换。
 */
@Component
@Slf4j
public class HeartRateHealthTools {

    private final HeartRateSeriesService series;

    public HeartRateHealthTools(HeartRateSeriesService series) {
        this.series = series;
    }

    /**
     * 单日心率曲线：全天统计 + 峰值/谷值（含持续时间）+ 分桶曲线 + 当天运动时段。
     *
     * <p>刻意只做单日。多日的心率概况 {@code get_daily_vitals} 已经给了（每天一行，
     * 含均值/最低/最高），在这里再开一个「多日 × 分桶」的口径，只会让两边数字打架。
     */
    @McpTool(name = "get_heart_rate_series",
            description = "查询某一天的自动心率曲线（华为 Dynamic Heart Rate）：全天采样点数、最低/平均/最高心率、"
                    + "首次与末次采样时刻、采样间隔中位数，最高与最低点落在几点几分、各持续了多久，"
                    + "按时间分桶的曲线序列，以及当天登记的运动时段。"
                    + "用于回答“那天心率曲线怎么样”“最高心率到多少、是几点”“夜里心率掉到多少”"
                    + "“心率是不是一直很高”“那天是不是只戴了半天表”。"
                    + "注意两点：① 采样密度不是固定的，早期每分钟一条（一天 1400+ 点），"
                    + "近期静息时 10 分钟一条、运动时加密（一天约 250 点），点数少不等于没数据，"
                    + "看 stats.medianGapMinutes；② 单点极值可能只是翻身或传感器噪声，"
                    + "peak.sustainedMinutes 小于 2 分钟就要存疑，notes 里会点明。"
                    + "多日/长期趋势请改用 get_daily_vitals，想升级到逐分钟原始点用 resolution=minute。")
    public Map<String, Object> getHeartRateSeries(
            @McpToolParam(description = "日期，格式 yyyy-MM-dd，默认取数据最新一天", required = false) String date,
            @McpToolParam(description = "曲线粒度：minute（每个原始采样点）、5min（默认）、15min、hour；"
                    + "数值越大返回的桶越少", required = false) String resolution) {
        String mode = HeartRateSeriesService.normalizeResolution(resolution);
        if (mode == null) {
            return invalidArgument("resolution", resolution,
                    "无法识别的粒度 \"" + resolution + "\"，支持 minute / 5min / 15min / hour；不传按 5min",
                    HeartRateSeriesService.resolutions());
        }

        LocalDate day = null;
        if (date != null && !date.isBlank()) {
            try {
                day = DateParser.parseDate(date.trim());
            } catch (RuntimeException e) {
                return invalidArgument("date", date, "无法解析日期 \"" + date + "\"，请使用 yyyy-MM-dd", null);
            }
        } else {
            day = series.latestDate();
            if (day == null) {
                return noData(null);
            }
        }

        HeartRateSeries result = series.series(day, mode);
        if (result == null) {
            return noData(day);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        // 用字符串而不是 LocalDate：项目只引了 jackson-databind、没引 jsr310 模块，
        // 直接放 LocalDate 会被当 POJO 展开成 {year, month, dayOfMonth...}。
        body.put("date", result.date().toString());
        body.put("resolution", result.resolution());
        body.put("bucketMinutes", result.bucketMinutes());
        body.put("stats", result.stats());
        body.put("peak", result.peak());
        body.put("lowest", result.lowest());
        body.put("bucketCount", result.buckets().size());
        body.put("buckets", result.buckets());
        if (!result.activities().isEmpty()) {
            body.put("activities", result.activities());
        }
        body.put("notes", result.notes());
        return body;
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

    private Map<String, Object> noData(LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", date == null
                ? "库里没有自动心率记录。"
                : date + " 没有自动心率采样，可能是没戴表、没同步，或那天不在导出范围内。");
        if (date != null) {
            result.put("date", date);
        }
        String covered = series.coveredRange();
        if (covered != null) {
            result.put("coveredRange", covered);
        }
        result.put("hint", "可用 get_daily_vitals 查看那几天的全天心率均值/极值是否也缺失。");
        return result;
    }
}
