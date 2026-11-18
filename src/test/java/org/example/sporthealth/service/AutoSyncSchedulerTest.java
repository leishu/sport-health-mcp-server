package org.example.sporthealth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.repository.HealthDataRepository;
import org.junit.jupiter.api.Test;

/**
 * 自动同步执行器：只验证它确实走仓储的 reload、失败不会抛给调度线程。
 */
class AutoSyncSchedulerTest {

    @Test
    void reloadsRepositoryAndReadsFreshDataset() {
        FakeRepository repository = new FakeRepository();
        AutoSyncScheduler scheduler = new AutoSyncScheduler(repository);

        scheduler.syncNow();

        assertEquals(1, repository.reloads);
        assertEquals(1, repository.datasetReads);
    }

    @Test
    void swallowsFailureSoTheScheduleKeepsRunning() {
        FakeRepository repository = new FakeRepository();
        repository.failure = new IllegalStateException("磁盘暂时不可用");
        AutoSyncScheduler scheduler = new AutoSyncScheduler(repository);

        assertDoesNotThrow(scheduler::syncNow);

        assertEquals(1, repository.reloads);
        assertEquals(0, repository.datasetReads, "同步失败时不该再读数据集");
    }

    private static final class FakeRepository implements HealthDataRepository {

        private int reloads;
        private int datasetReads;
        private RuntimeException failure;

        @Override
        public RunDataset dataset() {
            datasetReads++;
            return new RunDataset(Instant.parse("2026-11-04T00:00:00Z"), "classpath:health-data",
                    List.of(), List.of(), List.of());
        }

        @Override
        public void reload() {
            reloads++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<TrackPoint> route(String runId) {
            return List.of();
        }
    }
}
