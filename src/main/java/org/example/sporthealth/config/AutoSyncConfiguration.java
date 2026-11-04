package org.example.sporthealth.config;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.service.AutoSyncScheduler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 注册后台自动同步任务。
 *
 * <p>用 {@link SchedulingConfigurer} 而不是在方法上写 {@code @Scheduled}：间隔与首次延迟
 * 来自 {@link AutoSyncProperties}，关闭开关时干脆不注册任务，避免留下一个空转的调度。
 *
 * <p>采用 fixed-delay 而不是 fixed-rate：上一次同步包含扫描、解析和天气 HTTP 调用，
 * 耗时不可控；fixed-delay 能保证两次执行不重叠，不会出现「上一轮还没跑完，下一轮又进来」。
 * 同步本身也在仓储里加了方法锁，手动 {@code reload_data} 与后台任务同样互斥。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AutoSyncProperties.class)
@Slf4j
public class AutoSyncConfiguration implements SchedulingConfigurer {

    private final AutoSyncScheduler scheduler;
    private final AutoSyncProperties properties;

    public AutoSyncConfiguration(AutoSyncScheduler scheduler, AutoSyncProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            log.info("自动同步已关闭（sport.health.auto-sync.enabled=false），仅保留手动 reload_data");
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                scheduler::syncNow, properties.interval(), properties.initialDelay()));
        log.info("自动同步已启用：每 {} 同步一次，启动后延迟 {} 开始",
                properties.interval(), properties.initialDelay());
    }
}
