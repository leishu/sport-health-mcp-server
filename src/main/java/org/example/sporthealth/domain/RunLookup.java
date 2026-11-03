package org.example.sporthealth.domain;

import java.util.List;

/**
 * 按用户输入查找跑步记录的结果。
 *
 * <p>之前的实现用 {@code contains} 兜底后直接 {@code findFirst()}，输入 {@code 2026}
 * 这种短前缀会随机命中一条。现在把“唯一命中 / 多条候选 / 未找到”三种状态显式区分开，
 * 由工具层决定如何回读给模型。
 *
 * @param run        唯一命中时非 null
 * @param candidates 命中多条时的候选记录；未找到时为空
 */
public record RunLookup(RunActivity run, List<RunActivity> candidates) {

    public RunLookup {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static RunLookup found(RunActivity run) {
        return new RunLookup(run, List.of());
    }

    public static RunLookup ambiguous(List<RunActivity> candidates) {
        return new RunLookup(null, candidates);
    }

    public static RunLookup notFound() {
        return new RunLookup(null, List.of());
    }

    public boolean isFound() {
        return run != null;
    }

    public boolean isAmbiguous() {
        return run == null && !candidates.isEmpty();
    }

    public List<String> candidateIds() {
        return candidates.stream().map(RunActivity::id).toList();
    }
}
