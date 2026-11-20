package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 趋势查询的完整结果，由服务层一次算好、工具层原样输出。
 *
 * @param points   按周期升序排列的点
 * @param trend    主指标的趋势结论
 * @param overall  整个区间的汇总（与 {@code get_sleep_summary} 同一套口径，可作基线）
 * @param coverage 数据来源覆盖情况
 * @param notes    给调用方看的读法提示，没有要注意的地方时为空
 */
public record SleepTrendReport(
        List<SleepTrendPoint> points,
        SleepTrend trend,
        SleepSummary overall,
        SleepCoverage coverage,
        List<String> notes) {

    /** 第一个点的日期，用于回显实际统计到哪一段；没有点时取区间起点。 */
    public LocalDate firstDay() {
        return points.isEmpty() ? null : points.get(0).from();
    }
}
