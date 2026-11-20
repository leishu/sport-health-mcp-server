package org.example.sporthealth.tools;

import java.time.Instant;
import java.util.List;

import org.example.sporthealth.domain.RunDataset;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.repository.HealthDataRepository;

/**
 * 用于服务层单测的空仓储：{@code dataset()} 返回空快照，{@code reload()} 什么都不做。
 * 避免需要真的跑导入的场景。
 */
final class EmptyHealthDataRepository implements HealthDataRepository {

    private final RunDataset dataset;

    EmptyHealthDataRepository() {
        this(new RunDataset(Instant.now(), "test", List.of(), List.of(), List.of()));
    }

    EmptyHealthDataRepository(RunDataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public RunDataset dataset() {
        return dataset;
    }

    @Override
    public void reload() {
    }

    @Override
    public List<TrackPoint> route(String runId) {
        return List.of();
    }
}
