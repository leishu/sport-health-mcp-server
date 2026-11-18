package org.example.sporthealth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.example.sporthealth.service.AutoSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 自动同步的调度注册：开关关闭时不注册任务，开启时按配置的 fixed-delay 注册。
 */
class AutoSyncConfigurationTest {

    private final AutoSyncScheduler scheduler = mock(AutoSyncScheduler.class);

    @Test
    void registersOneFixedDelayTaskWhenEnabled() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new AutoSyncConfiguration(scheduler,
                new AutoSyncProperties(true, Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .configureTasks(registrar);

        List<IntervalTask> tasks = registrar.getFixedDelayTaskList();
        assertEquals(1, tasks.size());
        assertEquals(Duration.ofMinutes(5), tasks.get(0).getIntervalDuration());
        assertEquals(Duration.ofSeconds(30), tasks.get(0).getInitialDelayDuration());
    }

    @Test
    void registersNothingWhenDisabled() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new AutoSyncConfiguration(scheduler,
                new AutoSyncProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .configureTasks(registrar);

        assertTrue(registrar.getFixedDelayTaskList().isEmpty());
    }
}
