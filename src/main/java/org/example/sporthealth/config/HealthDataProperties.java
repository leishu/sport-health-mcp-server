package org.example.sporthealth.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据源与分页配置（前缀 {@code sport.health}）。
 *
 * <p>紧凑构造器负责兜底：空值回落到默认值，非正的分页参数按默认值处理，
 * 并保证 {@code maxPageSize >= defaultPageSize}。
 *
 * @param dataDir         TCX 文件所在目录，支持 {@code classpath:} 前缀或本地绝对路径
 * @param zoneId          TCX 内时间为 UTC，换算成本地日期所用时区
 * @param defaultPageSize 未显式传 limit 时的返回条数
 * @param maxPageSize     列表接口允许的最大条数
 */
@ConfigurationProperties(prefix = "sport.health")
public record HealthDataProperties(
        String dataDir,
        String zoneId,
        int defaultPageSize,
        int maxPageSize) {

    public static final String DEFAULT_DATA_DIR = "classpath:health-data";
    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int DEFAULT_MAX_PAGE_SIZE = 200;

    public HealthDataProperties {
        dataDir = isBlank(dataDir) ? DEFAULT_DATA_DIR : dataDir.trim();
        zoneId = isBlank(zoneId) ? DEFAULT_ZONE_ID : zoneId.trim();
        defaultPageSize = defaultPageSize <= 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
        maxPageSize = maxPageSize <= 0 ? DEFAULT_MAX_PAGE_SIZE : maxPageSize;
        if (maxPageSize < defaultPageSize) {
            maxPageSize = defaultPageSize;
        }
    }

    /** 解析后的时区对象；非法时区会在启动阶段抛异常，避免运行期才暴露。 */
    public ZoneId zone() {
        return ZoneId.of(zoneId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
