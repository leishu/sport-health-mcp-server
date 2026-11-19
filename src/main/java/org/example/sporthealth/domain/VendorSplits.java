package org.example.sporthealth.domain;

import java.util.List;

/**
 * 一次跑步的厂商官方分段配速（目前只有华为 {@code pm2}）。
 *
 * <p>只保留能解出「累计距离 + 配速」的行；华为导出里没有 {@code pm2} 的记录（例如 TCX 来源）
 * 得到 {@link #usable()} 为 false 的空结果。
 *
 * @param runId                  记录 id
 * @param source                 来源标记，例如 {@code HUAWEI_PM2}
 * @param splitMeters            厂商分段长度（米），华为固定 1000
 * @param totalKm                分段累计覆盖的距离（公里）
 * @param splits                 分段列表，按终点距离升序，最后一段可能不满
 * @param avgPaceSecondsPerKm    按距离加权的整体配速（秒/公里），无分段时为 null
 * @param avgPaceText            易读配速
 */
public record VendorSplits(
        String runId,
        String source,
        double splitMeters,
        double totalKm,
        List<VendorSplit> splits,
        Double avgPaceSecondsPerKm,
        String avgPaceText) {

    /** 没有可用分段：出厂值也走这个形态，调用方不用判空。 */
    public static VendorSplits empty(String runId, String source) {
        return new VendorSplits(runId, source, 0d, 0d, List.of(), null, null);
    }

    public boolean usable() {
        return !splits.isEmpty();
    }
}
