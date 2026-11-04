package org.example.sporthealth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 后台自动同步配置（前缀 {@code sport.health.auto-sync}）。
 *
 * <p>自动同步复用 {@link org.example.sporthealth.repository.HealthDataRepository#reload()}：
 * 扫描 TCX 目录、按指纹增量导入、重建内存快照，并在导入结束后补一轮天气。
 * 它与手动 {@code reload_data} 走完全相同的路径，因此不会出现两套同步逻辑。
 *
 * <p>默认开启、每 5 分钟一次、启动后延迟 30 秒开始。间隔用 Spring 的 Duration 格式
 * （{@code 30s}、{@code 5m}、{@code PT10M} 均可）。非正数会回落到默认值，
 * 避免把调度器配成忙循环。
 *
 * @param enabled      是否启用后台自动同步
 * @param interval     两次同步之间的间隔（上一次结束后开始计时）
 * @param initialDelay 应用启动后第一次同步的延迟
 */
@ConfigurationProperties(prefix = "sport.health.auto-sync")
public record AutoSyncProperties(
        @DefaultValue("true") boolean enabled,
        Duration interval,
        Duration initialDelay) {

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(30);

    public AutoSyncProperties {
        interval = positiveOr(interval, DEFAULT_INTERVAL);
        initialDelay = nonNegativeOr(initialDelay, DEFAULT_INITIAL_DELAY);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration nonNegativeOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}
