package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.ActivitySummary;
import org.example.sporthealth.domain.DailyActivityDay;
import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.RunSummary;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.SleepSummary;
import org.example.sporthealth.domain.VitalsMetricStat;
import org.example.sporthealth.service.DailyActivityService;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.service.SleepAnalyticsService;
import org.example.sporthealth.service.VitalsAnalyticsService;
import org.example.sporthealth.support.DateParser;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 健康周报：一次回答「这周我整体怎么样」——步数、睡眠、体征、训练。
 *
 * <p>每个子系统仍有独立工具（{@code get_daily_activity}、{@code get_sleep_summary}、
 * {@code get_daily_vitals}、{@code get_running_summary}）；本工具是周报聚合版，
 * 适合「发邮件/周报」这类“一眼看全”的场景。时间粒度是自然周，跨度默认 7 天。
 */
@Component
@Slf4j
public class HealthDigestTools {

    private static final int DEFAULT_SPAN_DAYS = 7;

    private final SleepAnalyticsService sleep;
    private final VitalsAnalyticsService vitals;
    private final DailyActivityService activity;
    private final RunAnalyticsService runs;

    public HealthDigestTools(SleepAnalyticsService sleep,
                             VitalsAnalyticsService vitals,
                             DailyActivityService activity,
                             RunAnalyticsService runs) {
        this.sleep = sleep;
        this.vitals = vitals;
        this.activity = activity;
        this.runs = runs;
    }

    /**
     * 周报：步数（包含跑步/走路分流）+ 睡眠均值 + 体征均值 + 训练记录。
     *
     * <p>各板块的「有值天数/记录数」一并输出，看区间范围真的够不够。
     */
    @McpTool(name = "get_weekly_health_report",
            description = "周健康报告：一次回答“这周我的步数、睡眠、体征与训练怎么样”。"
                    + "输出步数（含走跑分流）、睡眠均值（评分/效率/深睡）、体征均值（静息心率/HRV）、"
                    + "训练记录（次数/距离/平均配速）与各板块的有效覆盖度。用于回答“这周有点废吗”"
                    + "“我这周比上周好吗”。")
    public Map<String, Object> getWeeklyHealthReport(
            @McpToolParam(description = "开始日期 yyyy-MM-dd，默认取数据最新一天往前 7 天", required = false) String startDate,
            @McpToolParam(description = "结束日期 yyyy-MM-dd，默认今天", required = false) String endDate) {
        LocalDate to = endDate != null && !endDate.isBlank()
                ? DateParser.parseDate(endDate.trim()) : LocalDate.now();
        LocalDate from = startDate != null && !startDate.isBlank()
                ? DateParser.parseDate(startDate.trim()) : to.minusDays(DEFAULT_SPAN_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 晚于结束日期 " + to);
        }
        DateRange range = new DateRange(from, to);

        List<DailyActivityDay> activityDays = activity.days(range);
        ActivitySummary steps = activity.summary(activityDays);
        List<SleepNight> nights = sleep.nights(range);
        SleepSummary sleepSummary = sleep.summarize(nights);
        List<DailyVitals> vitalsDays = vitals.days(range);
        Map<String, VitalsMetricStat> vitalsMetrics = vitals.metrics(range, vitalsDays);
        RunSummary runsSummary = runs.summary(range);

        if (steps.days() == 0 && nights.isEmpty() && vitalsDays.isEmpty() && runsSummary.dayCount() == 0) {
            return Map.of("status", "NO_DATA",
                    "message", "区间 " + range.text() + " 没有任何健康数据；请放宽区间或先用 reload_data 同步");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "OK");
        report.put("range", range.text());
        report.put("steps", mapOf(steps));
        report.put("sleep", mapOf(sleepSummary));
        report.put("vitals", vitalsMetrics);
        report.put("runs", mapOf(runsSummary));
        report.put("text", buildText(steps, sleepSummary, vitalsMetrics, runsSummary));
        return report;
    }

    private static Map<String, Object> mapOf(ActivitySummary summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("daysWithData", summary.days());
        m.put("totalSteps", summary.totalSteps());
        m.put("averageSteps", summary.averageSteps());
        m.put("medianSteps", summary.medianSteps());
        m.put("minSteps", summary.minSteps());
        m.put("maxSteps", summary.maxSteps());
        m.put("totalActiveMinutes", summary.totalActiveMinutes());
        m.put("averageActiveMinutes", summary.averageActiveMinutes());
        return m;
    }

    private static Map<String, Object> mapOf(SleepSummary summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nights", summary.nights());
        m.put("reportNights", summary.reportNights());
        m.put("avgAsleepMinutes", summary.avgAsleepMinutes());
        m.put("avgAsleepText", summary.avgAsleepText());
        m.put("avgScore", summary.avgScore());
        m.put("avgEfficiencyPercent", summary.avgEfficiencyPercent());
        m.put("avgDeepPercent", summary.avgDeepPercent());
        m.put("avgRemPercent", summary.avgRemPercent());
        return m;
    }

    private static Map<String, Object> mapOf(RunSummary summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dayCount", summary.dayCount());
        m.put("runCount", summary.runCount());
        m.put("recordedDays", summary.recordedDays());
        m.put("totalDistanceKm", summary.totalDistanceKm());
        m.put("avgDistanceKm", summary.avgDistanceKm());
        m.put("avgDurationMinutes", summary.avgDurationMinutes());
        m.put("avgPaceSecondsPerKm", summary.avgPaceSecondsPerKm());
        return m;
    }

    private static String buildText(ActivitySummary steps, SleepSummary sleep,
                                    Map<String, VitalsMetricStat> vitalsMetrics,
                                    RunSummary runs) {
        StringBuilder sb = new StringBuilder("本周健康周报：");
        sb.append("步数 ").append(steps.totalSteps());
        if (steps.days() > 0) {
            sb.append("（日均 ").append(steps.averageSteps()).append("）");
        }
        sb.append('；');
        if (sleep.nights() > 0) {
            sb.append("睡眠平均 ").append(sleep.avgAsleepText());
            if (sleep.avgScore() != null) {
                sb.append("，评分 ").append(sleep.avgScore());
            }
            sb.append('；');
        } else {
            sb.append("本周无睡眠数据；");
        }
        VitalsMetricStat rhr = vitalsMetrics.get("restingHeartRate");
        if (rhr != null && rhr.average() != null) {
            sb.append("静息心率 ").append(rhr.average()).append(" bpm；");
        }
        VitalsMetricStat hrv = vitalsMetrics.get("hrv");
        if (hrv != null && hrv.average() != null) {
            sb.append("HRV ").append(hrv.average()).append(" ms；");
        }
        if (runs.runCount() > 0) {
            sb.append("跑步 ").append(runs.runCount()).append(" 次，共 ")
                    .append((long) Math.round(runs.totalDistanceKm())).append(" km");
        } else {
            sb.append("本周无跑步数据");
        }
        return sb.toString();
    }
}
