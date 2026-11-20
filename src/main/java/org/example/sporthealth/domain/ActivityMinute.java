package org.example.sporthealth.domain;

/**
 * 某一天里的一分钟记录（已去重）。
 *
 * <p>{@code time} / {@code endTime} 是本地墙上时间（+08:00）的 {@code HH:mm}，不跨天：
 * 这一批数据里没有跨零点的分钟记录，凌晨活动会落在当天的 {@code 00:xx}。
 *
 * <p>{@code sourceRows} &gt; 1 说明这一分钟原本有两条记录 —— 全库这样的分钟无一例外是
 * <b>两台设备同时记了同一分钟</b>（手表 + 手机），返回值里只保留步数较大的那条，
 * 合并掉的条数会在 {@code notes} 里交代。
 *
 * @param time           这一分钟的起点，{@code HH:mm}
 * @param endTime        结束时间，正常是下一分钟
 * @param sportType      原始 {@code sport_type} 码：2/5 走路、4 跑步、13 站立事件
 * @param type           归一后的类型键：{@code walk} / {@code run} / {@code stand} / {@code other}
 * @param label          类型中文名，直接可读：走路 / 跑步 / 站立事件 / 其它
 * @param steps          这一分钟的步数
 * @param distanceMeters 这一分钟的距离（米）
 * @param caloriesKcal   这一分钟的活动热量（千卡）
 * @param intensity      官方是否标记为中高强度；同一分钟两台设备取并集（任一台标了就算）
 * @param stand          是否是站立事件（整点触发、0 步）
 * @param deviceCode     记录来源设备
 * @param sourceRows     这一分钟原本有几条记录；&gt;1 表示跨设备重复、已合并
 */
public record ActivityMinute(
        String time,
        String endTime,
        int sportType,
        String type,
        String label,
        int steps,
        double distanceMeters,
        double caloriesKcal,
        boolean intensity,
        boolean stand,
        String deviceCode,
        int sourceRows) {
}
