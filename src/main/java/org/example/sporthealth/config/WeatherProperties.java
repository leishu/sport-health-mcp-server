package org.example.sporthealth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Open-Meteo 天气回填配置（前缀 {@code sport.weather}）。
 *
 * <p>紧凑构造器负责兜底：空 URL 回落到官方端点，非正数的时间参数按默认值处理，
 * 保证 {@code 0} 或负数不会变成「永不重试」或「请求不超时」这类危险配置。
 *
 * <p>{@code archiveLagDays} 把 forecast 窗口<b>最老的</b> {@code lag} 天改交给 archive：
 * 分界线是 {@code 今天 - 窗口 + 延迟}，archive 对这段历史（几十天前）数据完整，
 * 而 forecast 的 92 天上限边缘未必可靠。它应当<b>远小于</b> {@code recentWindowDays}，
 * 默认的 92 / 5 是安全的；配成不小于窗口时会按 0 处理，只保留原始窗口。
 * 注意：若窗口本身小于 archive 约 5 天的延迟，窗口外最近的日期仍会落进 archive 的盲区，
 * 这个参数帮不上忙——保持默认窗口即可。
 *
 * @param enabled             是否启用天气回填；关掉后导入流程完全不碰网络
 * @param forecastBaseUrl     预报端点，覆盖「今天往前 {@code recentWindowDays} 天」的数据
 * @param archiveBaseUrl      历史存档端点，覆盖更早的数据（有约 5 天延迟）
 * @param recentWindowDays    距今多少天内走 forecast，超出后走 archive
 * @param archiveLagDays      把 forecast 窗口最老的这么多天改交给 archive，应远小于窗口
 * @param timeout             单次 HTTP 请求超时
 * @param retryFailedAfterDays 失败记录的重试间隔；成功与「无数据」不参与重试
 * @param maxRunsPerSync      单次同步最多回填多少条记录，防止首次全量导入时把配额打满
 */
@ConfigurationProperties(prefix = "sport.weather")
public record WeatherProperties(
        boolean enabled,
        String forecastBaseUrl,
        String archiveBaseUrl,
        int recentWindowDays,
        int archiveLagDays,
        Duration timeout,
        int retryFailedAfterDays,
        int maxRunsPerSync) {

    public static final String DEFAULT_FORECAST_BASE_URL = "https://api.open-meteo.com/v1/forecast";
    public static final String DEFAULT_ARCHIVE_BASE_URL = "https://archive-api.open-meteo.com/v1/archive";
    public static final int DEFAULT_RECENT_WINDOW_DAYS = 92;
    public static final int DEFAULT_ARCHIVE_LAG_DAYS = 5;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_RETRY_FAILED_AFTER_DAYS = 7;
    public static final int DEFAULT_MAX_RUNS_PER_SYNC = 50;

    public WeatherProperties {
        forecastBaseUrl = isBlank(forecastBaseUrl) ? DEFAULT_FORECAST_BASE_URL : forecastBaseUrl.trim();
        archiveBaseUrl = isBlank(archiveBaseUrl) ? DEFAULT_ARCHIVE_BASE_URL : archiveBaseUrl.trim();
        recentWindowDays = recentWindowDays <= 0 ? DEFAULT_RECENT_WINDOW_DAYS : recentWindowDays;
        archiveLagDays = archiveLagDays < 0 ? DEFAULT_ARCHIVE_LAG_DAYS : archiveLagDays;
        // 延迟吞掉整个窗口时它已经失去意义，退回零余量：见类注释
        if (archiveLagDays >= recentWindowDays) {
            archiveLagDays = 0;
        }
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        retryFailedAfterDays = retryFailedAfterDays <= 0 ? DEFAULT_RETRY_FAILED_AFTER_DAYS : retryFailedAfterDays;
        maxRunsPerSync = maxRunsPerSync <= 0 ? DEFAULT_MAX_RUNS_PER_SYNC : maxRunsPerSync;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
