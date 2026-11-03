package org.example.sporthealth.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次数据加载的结果快照。{@link #activities} 按开始时间倒序，新的在前。
 *
 * <p>快照是只读的：{@code reload_data} 会整体替换一份新快照，不会原地修改。
 *
 * @param loadedAt    本次扫描完成的时间（UTC）
 * @param dataDir     实际使用的数据目录配置值
 * @param sourceFiles 成功解析的文件名列表
 * @param activities  全部运动记录（不含轨迹点，轨迹按需加载）
 * @param warnings    解析告警：整文件失败、字段级问题、目录为空等
 */
public record RunDataset(
        Instant loadedAt,
        String dataDir,
        List<String> sourceFiles,
        List<RunActivity> activities,
        List<String> warnings) {

    /** 记录条数，等价于 {@code activities.size()}。 */
    public int runCount() {
        return activities.size();
    }
}
