package org.example.sporthealth.repository;

import java.util.List;

import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;

public interface HealthDataRepository {

    /** 已解析的跑步记录（不含轨迹点，避免数据量过大）。 */
    RunDataset dataset();

    /** 重新扫描并解析数据目录。 */
    void reload();

    /** 指定记录的完整轨迹点，找不到时返回空列表。 */
    List<TrackPoint> route(String runId);
}
