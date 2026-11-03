package org.example.sporthealth.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具层的基础设施配置。
 *
 * <p>只注册一个 {@link Clock}：日期区间解析（例如“最近 7 天”）依赖它，
 * 测试里可以替换成固定时钟，生产环境用系统时区时钟。
 */
@Configuration
@EnableConfigurationProperties(HealthDataProperties.class)
public class McpToolsConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
