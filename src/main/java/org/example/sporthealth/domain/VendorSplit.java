package org.example.sporthealth.domain;

/**
 * 厂商（华为）自算的一段配速，来自 {@code track_stream} 的 {@code pm2} 流。
 *
 * <p>与 {@link RunSplit} 的区别：{@link RunSplit} 由 GPS 轨迹插值切分，这里是手表/App 自己的
 * 分段结果，两者口径不同（不含插值、按设备累计距离切分），因此只用于互相校验。
 *
 * <p>{@code pm2} 行的语义：{@code d} 是该段终点的累计距离，{@code p} 是该段配速（秒/公里）；
 * 整公里段时 {@code p} 与用时秒数相同，末段不足一公里时 {@code p} 仍是配速。
 *
 * @param index            段序号，从 1 开始
 * @param fromKm           起始距离（公里）
 * @param toKm             结束距离（公里）
 * @param distanceKm       本段距离（公里）
 * @param durationSeconds  本段用时（秒），由配速×距离折算
 * @param paceSecondsPerKm 本段配速（秒/公里）
 * @param paceText         配速文本，例如 5'45"/km
 * @param partial          是否不满整公里（华为只给 1 公里分段）
 * @param pointIndex       华为在该段终点处的轨迹点序号，缺失时为 null
 */
public record VendorSplit(
        int index,
        double fromKm,
        double toKm,
        double distanceKm,
        double durationSeconds,
        double paceSecondsPerKm,
        String paceText,
        boolean partial,
        Integer pointIndex) {
}
