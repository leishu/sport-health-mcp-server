package org.example.sporthealth.domain;

import java.util.List;

/**
 * 通用分页结果。{@code offset} 会被夹取到合法范围，越界时返回空列表而不是抛异常。
 *
 * @param items  当前页数据
 * @param total  总条数
 * @param offset 实际生效的偏移（已夹取）
 * @param limit  请求的每页条数
 * @param <T>    元素类型
 */
public record Page<T>(List<T> items, int total, int offset, int limit) {

    /** 是否还有下一页，等价于 {@code offset + items.size() < total}。 */
    public boolean hasMore() {
        return offset + items.size() < total;
    }

    /**
     * 对完整列表做内存分页。
     *
     * @param all    完整列表，按调用方期望的顺序排好
     * @param offset 偏移，负数按 0 处理
     * @param limit  每页条数，非正数时返回空页
     */
    public static <T> Page<T> of(List<T> all, int offset, int limit) {
        int from = Math.clamp(offset, 0, all.size());
        int to = Math.min(from + limit, all.size());
        return new Page<>(all.subList(from, to), all.size(), from, limit);
    }
}
