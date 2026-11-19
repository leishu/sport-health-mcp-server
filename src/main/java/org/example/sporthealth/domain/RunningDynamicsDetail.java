package org.example.sporthealth.domain;

import java.util.List;

/**
 * 单次跑步的跑姿详情：整次汇总 + 按距离分段 + 前后半程对比。
 *
 * @param run        整次汇总（触地时间/垂直振幅/左右平衡/步幅/步频）
 * @param splitMeters 分段距离（米）
 * @param segments   按距离切出的段，轨迹不可用时为空
 * @param firstHalf  前半程（按轨迹距离均分），不可用时为 null
 * @param secondHalf 后半程，不可用时为 null
 */
public record RunningDynamicsDetail(
        RunningDynamics run,
        double splitMeters,
        List<DynamicsSegment> segments,
        DynamicsSegment firstHalf,
        DynamicsSegment secondHalf) {
}
