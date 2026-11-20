package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.DailyActivityDay;
import org.example.sporthealth.domain.DailyVitals;
import org.example.sporthealth.domain.SleepNight;
import org.example.sporthealth.domain.VitalsMetricStat;
import org.example.sporthealth.domain.TrainingLoad;
import org.example.sporthealth.service.DailyActivityService;
import org.example.sporthealth.service.RunAnalyticsService;
import org.example.sporthealth.service.SleepAnalyticsService;
import org.example.sporthealth.service.VitalsAnalyticsService;
import org.example.sporthealth.support.DateRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 跨域健康状态工具：睡眠 + 体征 + 训练负荷 + 今日活动量综合判断。
 *
 * <p>每个子系统各有自己的工具（{@code get_sleep_summary}、{@code get_daily_vitals}、
 * {@code get_training_load}、{@code get_daily_activity}）；本工具是「结合它们回答
 * “我今天适合训练吗”“今晚睡得怎么样、体征够不够”这类跨域问题」。
 * 注意所有评估只是<b>数据呈现 + 说明信息</b>：{@code riskLevel} 来自训练负荷模型，
 * 恢复建议由工具输出供用户/模型判断，本工具不替用户拍板“高/中风险”，而是给出覆盖度
 * 与置信度。
 */
@Component
@Slf4j
public class RecoveryTools {

    private final SleepAnalyticsService sleep;
    private final VitalsAnalyticsService vitals;
    private final DailyActivityService activity;
    private final RunAnalyticsService runs;

    public RecoveryTools(SleepAnalyticsService sleep,
                         VitalsAnalyticsService vitals,
                         DailyActivityService activity,
                         RunAnalyticsService runs) {
        this.sleep = sleep;
        this.vitals = vitals;
        this.activity = activity;
        this.runs = runs;
    }

    /**
     * 跨域恢复状态：最近睡眠（score/效率/HRV/深睡）+ 静息心率/HRV均值 + 训练负荷（ACWR/风险等级）
     * + 今天活动量。适合回答“我今天适合再强度训练吗”。
     *
     * <p>注意每类数据的覆盖度：sleepNights 是今夜次数、vitalsDays 是有静息心率的天数，
     * dataSufficient=false 时负荷结论不能当“高风险”直接面授。
     */
    @McpTool(name = "get_recovery_status",
            description = "跨域恢复状态：最近一夜的睡眠评分/效率/HRV/深睡时长、区间静息心率均值、"
                    + "最近 7 天训练负荷（急性/慢性负荷、ACWR、风险等级与置信度），"
                    + "以及今天的活动量（步数/中高强度）。用于回答“我今天适合强度训练吗”"
                    + "“最近睡眠加训练是否太多”。各指标是单点描述+覆盖度，不是整体诊断。")
    public Map<String, Object> getRecoveryStatus(
            @McpToolParam(description = "开始日期 yyyy-MM-dd，默认取睡眠数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期 yyyy-MM-dd，默认取睡眠数据最新一天", required = false) String endDate) {
        DateRange range = sleep.resolveRange(startDate, endDate);
        List<SleepNight> nights = sleep.nights(range);
        List<DailyVitals> vitalsDays = vitals.days(range);
        TrainingLoad load = runs.trainingLoad(range.to(), "distance");
        Integer stepsToday = todaySteps();

        boolean hasSleep = !nights.isEmpty();
        boolean hasVitals = !vitalsDays.isEmpty();
        boolean hasTraining = load.acute().runCount() > 0 || load.chronic().runCount() > 0;
        if (!hasSleep && !hasVitals && !hasTraining) {
            return Map.of("status", "NO_DATA",
                    "message", "区间 " + range.text() + " 没有可用的睡眠、体征或训练数据");
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("sleepNights", nights.size());
        coverage.put("vitalsDays", vitalsDays.size());
        coverage.put("acuteTrainingDays", load.acute().runCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("range", range.text());
        result.put("coverage", coverage);

        if (hasSleep) {
            SleepNight latest = nights.getLast();
            Map<String, Object> latestSleep = new LinkedHashMap<>();
            latestSleep.put("date", latest.date().toString());
            latestSleep.put("timeInBedMinutes", latest.timeInBedMinutes());
            latestSleep.put("asleepMinutes", latest.asleepMinutes());
            latestSleep.put("deepMinutes", latest.deepMinutes());
            latestSleep.put("lightMinutes", latest.lightMinutes());
            latestSleep.put("remMinutes", latest.remMinutes());
            latestSleep.put("awakeMinutes", latest.awakeMinutes());
            latestSleep.put("wakeCount", latest.wakeCount());
            latestSleep.put("score", latest.score());
            latestSleep.put("efficiencyPercent", latest.efficiencyPercent());
            latestSleep.put("hrvMs", latest.hrvMs());
            latestSleep.put("spo2Percent", latest.spo2Percent());
            result.put("latestSleep", latestSleep);
        } else {
            result.put("latestSleep", null);
        }

        VitalsMetricStat resting = vitals.metrics(range, vitalsDays).get("restingHeartRate");
        VitalsMetricStat hrv = vitals.metrics(range, vitalsDays).get("hrv");
        if (hasVitals) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("restingHeartRateAvg", resting == null ? null : resting.average());
            v.put("hrvAvg", hrv == null ? null : hrv.average());
            v.put("daysWithRestingHeartRate", resting == null ? null : resting.count());
            v.put("daysWithHrv", hrv == null ? null : hrv.count());
            result.put("vitals", v);
        } else {
            result.put("vitals", null);
        }

        Map<String, Object> training = new LinkedHashMap<>();
        training.put("riskLevel", load.riskLevel());
        training.put("dataSufficient", load.dataSufficient());
        training.put("confidence", load.confidence());
        training.put("acuteLoadKm", load.acute().load());
        training.put("chronicLoadKm", load.chronic().load());
        training.put("acwr", load.acwr());
        training.put("recommendation", load.recommendation());
        result.put("training", training);

        result.put("stepsToday", stepsToday);
        result.put("summary", buildSummary(result, stepsToday));
        return result;
    }

    private Integer todaySteps() {
        Optional<DateRange> covered = activity.coveredRange();
        if (covered.isEmpty()) {
            return null;
        }
        LocalDate last = covered.get().to();
        List<DailyActivityDay> today = activity.days(new DateRange(last, last));
        if (today.isEmpty()) {
            return null;
        }
        return today.getFirst().steps();
    }

    private static String buildSummary(Map<String, Object> result, Integer stepsToday) {
        StringBuilder sb = new StringBuilder("恢复状态：");
        Object latestSleep = result.get("latestSleep");
        if (latestSleep instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sleepMap = (Map<String, Object>) latestSleep;
            Object score = sleepMap.get("score");
            if (score != null) {
                sb.append("最近睡眠评分 ").append(score).append("，");
            }
            Object hrvMs = sleepMap.get("hrvMs");
            if (hrvMs != null) {
                sb.append("HRV ").append(hrvMs).append(" ms，");
            }
        }
        Object rhrAvg = vitalsField(result);
        if (rhrAvg != null) {
            sb.append("静息心率 ").append(rhrAvg).append(" bpm，");
        }
        Map<?, ?> training = (Map<?, ?>) result.get("training");
        if (training != null) {
            sb.append("训练负荷 ").append(training.get("riskLevel"));
            Object acwr = training.get("acwr");
            if (acwr != null) {
                sb.append("（ACWR ").append(acwr).append("）");
            }
            String recommendation = (String) training.get("recommendation");
            if (recommendation != null) {
                sb.append(' ').append(recommendation);
            }
            sb.append(';');
        }
        if (stepsToday != null) {
            sb.append("今日步数 ").append(stepsToday);
        }
        return sb.toString();
    }

    private static Object vitalsField(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> vitals = (Map<String, Object>) result.get("vitals");
        return vitals == null ? null : vitals.get("restingHeartRateAvg");
    }
}
