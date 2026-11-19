package org.example.sporthealth.repository;

import java.util.List;

import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.domain.TrackStream;

public interface HealthDataRepository {

    /** 已解析的跑步记录（不含轨迹点，避免数据量过大）。 */
    RunDataset dataset();

    /** 重新扫描并解析数据目录。 */
    void reload();

    /** 指定记录的完整轨迹点，找不到时返回空列表。 */
    List<TrackPoint> route(String runId);

    /**
     * 指定记录的原始流（{@code track_stream}），按 {@code seq} 升序。
     *
     * <p>这些流没有逐点时间结构（每 5 秒速度、跑姿、华为自算的单公里配速等），
     * 只能原样取出由分析层解释。
     *
     * @param streamType 流类型；为 null 或空时返回该记录的全部流
     * @return 找不到记录或该类型没有数据时返回空列表，不返回 null
     */
    default List<TrackStream> streams(String runId, String streamType) {
        // 非 SQLite 仓储（TCX 内存仓储、测试替身）不保存这类流，默认没有
        return List.of();
    }
}
