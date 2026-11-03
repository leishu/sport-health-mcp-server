package org.example.sporthealth.importer;

import java.util.List;

import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.TrackPoint;

/**
 * 单个 TCX Activity 的解析结果，轨迹与摘要分开保存，避免列表接口返回上千个轨迹点。
 *
 * @param warnings 字段级解析问题（缺时间、非法数字、缺坐标等），会汇总到
 *                 {@code RunDataset#warnings}，方便在 describe_data_source 里排查脏数据
 */
public record ParsedRun(RunActivity activity, List<TrackPoint> trackPoints, List<String> warnings) {
}
