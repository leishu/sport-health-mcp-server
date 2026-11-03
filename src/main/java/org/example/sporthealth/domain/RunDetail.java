package org.example.sporthealth.domain;

/**
 * 单次运动详情：摘要 + 易读文本 + 可选的分页轨迹。
 *
 * @param activity  运动摘要
 * @param paceText  配速文本，例如 5'45"/km；缺配速时为 null
 * @param speedText 速度文本，例如 10.4 km/h；缺速度时为 null
 * @param route     分页后的轨迹点，未请求轨迹时为 null
 */
public record RunDetail(
        RunActivity activity,
        String paceText,
        String speedText,
        Page<TrackPoint> route) {
}
