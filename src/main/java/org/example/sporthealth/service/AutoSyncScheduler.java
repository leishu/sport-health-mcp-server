package org.example.sporthealth.service;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.repository.HealthDataRepository;
import org.springframework.stereotype.Component;

/**
 * 后台自动同步的执行器：只负责调用仓储的 {@link HealthDataRepository#reload()}，
 * 不复制任何导入逻辑。
 *
 * <p>调度由 {@link org.example.sporthealth.config.AutoSyncConfiguration} 注册。把执行逻辑
 * 单独抽出来，既方便单测直接调用，也让「什么时候跑」与「跑什么」互不耦合。
 *
 * <p>异常在这里被吞掉并记录：后台任务失败不应该让调度线程退出，也不应该影响
 * 已经装载的上一份快照。下一次触发会继续尝试。
 */
@Component
@Slf4j
public class AutoSyncScheduler {

    private final HealthDataRepository repository;

    public AutoSyncScheduler(HealthDataRepository repository) {
        this.repository = repository;
    }

    /** 执行一次同步；失败只记录日志，不向调度器抛出。 */
    public void syncNow() {
        long started = System.nanoTime();
        try {
            repository.reload();
            RunDataset dataset = repository.dataset();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (dataset.warnings().isEmpty()) {
                log.info("自动同步完成：{} 条记录，耗时 {} ms", dataset.runCount(), elapsedMs);
            } else {
                log.warn("自动同步完成：{} 条记录、{} 条告警，耗时 {} ms；首条告警：{}",
                        dataset.runCount(), dataset.warnings().size(), elapsedMs,
                        dataset.warnings().get(0));
            }
        } catch (RuntimeException e) {
            log.error("自动同步失败，继续使用上一份快照；下次触发会重试", e);
        }
    }
}
