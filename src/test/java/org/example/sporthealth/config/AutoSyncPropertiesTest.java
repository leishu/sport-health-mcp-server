package org.example.sporthealth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

/**
 * 自动同步配置绑定：默认开启、Duration 解析、非法间隔回落。
 */
class AutoSyncPropertiesTest {

    @Test
    void bindsCustomDurationsAndDefaultsToEnabled() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("sport.health.auto-sync.interval", "10m")
                .withProperty("sport.health.auto-sync.initial-delay", "45s");

        AutoSyncProperties properties = bind(environment);

        assertTrue(properties.enabled(), "没有显式关闭时默认开启");
        assertEquals(Duration.ofMinutes(10), properties.interval());
        assertEquals(Duration.ofSeconds(45), properties.initialDelay());
    }

    @Test
    void canBeDisabled() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("sport.health.auto-sync.enabled", "false");

        assertFalse(bind(environment).enabled());
    }

    @Test
    void nonPositiveIntervalFallsBackToDefault() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("sport.health.auto-sync.interval", "0s")
                .withProperty("sport.health.auto-sync.initial-delay", "0s");

        AutoSyncProperties properties = bind(environment);

        assertEquals(AutoSyncProperties.DEFAULT_INTERVAL, properties.interval());
        assertEquals(Duration.ZERO, properties.initialDelay(), "首次延迟允许为 0");
    }

    private static AutoSyncProperties bind(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("sport.health.auto-sync", AutoSyncProperties.class)
                .get();
    }
}
