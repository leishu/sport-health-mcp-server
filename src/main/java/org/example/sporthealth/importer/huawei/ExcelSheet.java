package org.example.sporthealth.importer.huawei;

import java.util.List;

/**
 * 一个 Excel 工作表的数据。
 *
 * @param name    工作表名
 * @param headers 已清洗、已去重的表头；空表头会被命名成 {@code column_N}
 * @param rows    数据行（不含表头），每行长度与 {@code headers} 一致，空单元格为空串
 */
public record ExcelSheet(String name, List<String> headers, List<List<String>> rows) {
}
