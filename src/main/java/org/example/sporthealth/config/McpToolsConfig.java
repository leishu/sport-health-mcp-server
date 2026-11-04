package org.example.sporthealth.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * MCP 工具层与外部数据源的基础设施配置。
 *
 * <p>注册三样东西：
 * <ul>
 *   <li>{@link Clock}：日期区间解析（例如「最近 7 天」）依赖它，测试里可以替换成固定时钟；</li>
 *   <li>{@link WebClient}：open-meteo 的 HTTP 客户端，复用 WebFlux 栈自带的实现，
 *       不额外引入 RestTemplate 或第三方 HTTP 库（自己建 builder，见方法注释）；</li>
 *   <li>天气回填配置 {@link WeatherProperties}：未配置时按「启用 + 官方端点」兜底，
 *       保证开箱即用，同时可用 {@code sport.weather.enabled=false} 一键关掉。</li>
 * </ul>
 *
 * <p>WebClient 不设全局超时：超时由 {@code OpenMeteoClient} 在单次请求上控制，
 * 避免影响后续可能加入的其它外部调用。
 */
@Configuration
@EnableConfigurationProperties({HealthDataProperties.class, WeatherProperties.class})
public class McpToolsConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * open-meteo 用的 HTTP 客户端。
     *
     * <p>这里用 {@link WebClient#builder()} 自己建，而不是注入 {@code WebClient.Builder}：
     * 那个 Builder 来自 {@code WebClientAutoConfiguration}，在 WebFlux 应用里
     * 它并非无条件注册（本项目实测启动时报 "No qualifying bean of type WebClient$Builder"）。
     * 单元测试走 {@code WebClient.create()} 完全绕过了容器，所以这个坑只有真正启动才会暴露。
     *
     * <p>代码里显式设 {@code maxInMemorySize}：默认 256KB 对「一整天逐小时 × 多坐标」够用，
     * 但 buffer 上限是超出时才报错的类型，宁可写死一个够大又不会失控的值。
     */
    @Bean
    @ConditionalOnMissingBean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
