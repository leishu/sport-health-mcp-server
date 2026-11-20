package org.example.sporthealth.domain;

/**
 * 华为「体育勋章」的一行：类型、等级与最近获得时间。
 *
 * <p>与 {@code medal.excel_row} 不同：同一类型 + 等级可能多次获得，查询时按 {@code take_date}
 * 排序展示最新的一条，不做全表聚合。
 */
public record Medal(String medalType,
                    String medalLevel,
                    Integer gainCount,
                    String takeDate,
                    String modifyTime) {
}
