package org.example.sporthealth.domain;

import java.util.Map;

/**
 * {@code track_stream} 里的一行原始流：华为 {@code detailData} 中没有逐点时间结构的采样。
 *
 * <p>典型用途是读华为自己算好的单公里配速（{@code pm2}）、跑姿（{@code rp}）等，
 * 这些数据没有落进 {@link TrackPoint}，只能原样取出来在分析层解释。
 *
 * @param runId      所属活动
 * @param streamType 流类型：{@code rs}/{@code rp}/{@code pm2}/{@code bpm2}/{@code evt} …
 * @param seq        同一活动同一流内的序号，保持原始行顺序
 * @param timeMillis 时间戳（毫秒）；该流的 {@code k} 不是时间时为 null
 * @param pointIndex 采样点的 k：{@code rs}/{@code rp} 是相对起跑的秒数，{@code pm2} 一类是终点轨迹点序号；没有时为 null
 * @param value      主数值：优先 {@code v}，其次 {@code p}
 * @param fields     该行的全部键值对，字段含义随流类型而定
 */
public record TrackStream(
        String runId,
        String streamType,
        int seq,
        Long timeMillis,
        Integer pointIndex,
        Double value,
        Map<String, String> fields) {
}
