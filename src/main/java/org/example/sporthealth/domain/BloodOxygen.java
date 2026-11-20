package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

import org.example.sporthealth.support.DateRange;

/**
 * 血氧的测量记录与覆盖情况。
 *
 * <p>这个指标的数据在库里<b>只有 12 天</b>（2026-06-12~17 与 2026-08-03~08 两段），
 * 之后设备就再没上报过。所以这里刻意不提供趋势判定 —— 两段数据之间隔了一个半月，
 * 拟合出来任何斜率都只是「设备开没开连续血氧」的记录，不是身体变化。
 *
 * @param range     实际查询区间
 * @param days      区间内有血氧记录的天数
 * @param records   区间内样本总数
 * @param firstDate 区间内首条记录日期
 * @param lastDate  区间内末条记录日期
 * @param daily     按天汇总
 * @param samples   明细样本（可能被截断，看 {@code truncated}）
 * @param truncated 明细是否被条数上限截断
 * @param lowEvents 设备下发的血氧偏低提醒
 * @param notes     口径提示
 */
public record BloodOxygen(
        DateRange range,
        int days,
        int records,
        LocalDate firstDate,
        LocalDate lastDate,
        List<Spo2Day> daily,
        List<VitalsSample> samples,
        boolean truncated,
        List<Spo2LowEvent> lowEvents,
        List<String> notes) {
}
