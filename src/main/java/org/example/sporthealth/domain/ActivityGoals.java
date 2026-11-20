package org.example.sporthealth.domain;

/**
 * 「活动量够不够」的判定：三个日目标 + 一个周目标，以及区间内各自达成多少天。
 *
 * <p>目标值取的是通用公共卫生口径，不是从数据里学出来的：
 * <ul>
 *   <li>步数 8000/天：华为默认的日常目标（<b>不是</b> 10000，那个数字源自营销而非证据）；</li>
 *   <li>中高强度 30 分钟/天：WHO 建议每周 150 分钟中等强度，摊到七天即 30 分钟；</li>
 *   <li>站立 12 次/天：华为的站立目标就是 12 次（每小时至少起身一次）。</li>
 * </ul>
 *
 * <p>{@code weeklyActiveMinutes} 取的是<b>区间末尾连续 7 天</b>（锚在该区间最后一个有记录的日子），
 * 不是自然周 —— 用户问「这周够不够」时往往只关心最近七天。检查的是 WHO 那条周线，
 * 所以它和「每天 30 分钟达成几天」可以同时成立也可以不一致（比如三天各 60 分钟、
 * 其余四天不动：周线达标，日目标只达成三天）。
 *
 * <p><b>窗口不满 7 天时周线不能当真</b>：查单日时这个窗口只有 1 天，合计 40 分钟当然
 * 「未达标」。所以另给 {@code weeklyActiveMinutesDays}，只有它等于 7 才把
 * {@code weeklyActiveMinutesMet} 当结论看。
 *
 * @param stepsPerDay                 步数日目标
 * @param activeMinutesPerDay         中高强度分钟日目标
 * @param standEventsPerDay           站立次数日目标
 * @param activeMinutesPerWeek        中高强度分钟周目标
 * @param daysMeetingSteps            区间内步数达标的天数
 * @param daysMeetingActiveMinutes    区间内中高强度达标的天数
 * @param daysMeetingStand            区间内站立达标的天数
 * @param weeklyActiveMinutesDays     末尾 7 天窗口里有记录的天数（不足 7 天时周线只能参考）
 * @param weeklyActiveMinutes         末尾 7 天的中高强度分钟合计
 * @param weeklyActiveMinutesMet      该合计是否达到周目标；窗口不足 7 天时为 false
 */
public record ActivityGoals(
        int stepsPerDay,
        int activeMinutesPerDay,
        int standEventsPerDay,
        int activeMinutesPerWeek,
        int daysMeetingSteps,
        int daysMeetingActiveMinutes,
        int daysMeetingStand,
        int weeklyActiveMinutesDays,
        int weeklyActiveMinutes,
        boolean weeklyActiveMinutesMet) {
}
