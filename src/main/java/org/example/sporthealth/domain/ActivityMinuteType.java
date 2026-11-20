package org.example.sporthealth.domain;

import java.util.List;

/**
 * 某一天里按运动类型汇总的分钟数。
 *
 * <p>{@code minutes} 是<b>记录条数</b>，也就是「大约几分钟」：一条记录正常覆盖 1 分钟
 * （站立事件例外，它 {@code duration=0} 只是整点标记）。
 *
 * <p>{@code sportTypes} 列出归到这个类型的原始码，方便对照「为什么换设备之后走路还在」——
 * 走路是 2 和 5 两个码，3 号设备报 2、另两台报 5。
 *
 * @param type            类型键：{@code walk} / {@code run} / {@code stand} / {@code other}
 * @param label           类型中文名
 * @param sportTypes      归入该类型的原始 {@code sport_type} 码（升序）
 * @param minutes         记录条数（≈ 分钟数）
 * @param steps           该类型的步数合计
 * @param distanceKm      该类型的距离合计（公里）
 * @param caloriesKcal    该类型的活动热量合计（千卡）
 * @param firstTime       该类型当天最早一分钟，{@code HH:mm}
 * @param lastTime        该类型当天最晚一分钟（结束时间），{@code HH:mm}
 */
public record ActivityMinuteType(
        String type,
        String label,
        List<Integer> sportTypes,
        int minutes,
        int steps,
        double distanceKm,
        double caloriesKcal,
        String firstTime,
        String lastTime) {
}
