package org.example.sporthealth.domain;

import java.time.LocalDate;

/**
 * 某一天的分钟级概览。
 *
 * <p>数值口径与 {@code daily_activity} 一致（都是「同一分钟只算一次」之后聚合的），
 * 所以这里的 {@code steps} 应当与 {@code get_daily_activity} 里同一天的 {@code steps} 相等；
 * 对不上说明派生表没重建。
 *
 * <p>{@code rawRows} 与 {@code records} 的差就是被合并掉的跨设备重复条数：
 * 全库 2 208 个分钟有两条记录，都是手表 + 手机同时记同一分钟。
 *
 * @param date            日期
 * @param weekday         星期几（中文，如「周日」）
 * @param rawRows         当天原始记录条数（含被合并的重复）
 * @param records         去重后的记录条数
 * @param activityMinutes 有活动的分钟数（不含站立事件）
 * @param steps           步数合计
 * @param distanceKm      距离合计（公里）
 * @param caloriesKcal    活动热量合计（千卡），不含基础代谢
 * @param activeMinutes   中高强度分钟数
 * @param standEvents     站立事件条数（{@code sportType=13}）
 * @param firstActivity   当天第一个活动分钟，{@code HH:mm}；只有站立事件时为 null
 * @param lastActivity    当天最后一个活动分钟的结束时间，{@code HH:mm}；同上
 * @param sparse          记录条数少于 20，多半是没戴表或导出那天还没过完
 */
public record ActivityMinuteDay(
        LocalDate date,
        String weekday,
        int rawRows,
        int records,
        int activityMinutes,
        int steps,
        double distanceKm,
        double caloriesKcal,
        int activeMinutes,
        int standEvents,
        String firstActivity,
        String lastActivity,
        boolean sparse) {
}
