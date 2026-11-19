package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 读取华为导出里的 Excel（.xls / .xlsx），把每个非空工作表转成「表头 + 数据行」。
 *
 * <p>SportsHealth 的 93 个 xls 里大多数只有表头没有数据，真正有内容的 37 张分散在
 * 不同文件里；HUAWEI_Research 的 Data.xlsx 则是 4 张汇总表。两者结构相同，
 * 所以用同一个解析器，不为每张表写定制模型——先完整落库，再在派生表里挑重点字段。
 *
 * <p>清洗规则来自真实数据：华为把 JSON 列存成 {@code "\t[{""a"":1}]"} 这种「前导 Tab +
 * 外层引号 + 双引号转义」的形态，不分青红皂白落库会让 {@code json_extract} 全部失效。
 * 这里统一去掉首尾空白、外层成对引号，并把 {@code ""} 还原成 {@code "}。
 */
@Component
public class ExcelWorkbookParser {

    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);

    /**
     * @param fileName 来源文件名，仅用于异常信息
     * @param input    Excel 输入流
     * @return 至少有一行数据的工作表；没有任何数据时返回空列表
     */
    public List<ExcelSheet> parse(String fileName, InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            List<ExcelSheet> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                ExcelSheet parsed = parseSheet(sheet);
                if (parsed != null) {
                    sheets.add(parsed);
                }
            }
            return sheets;
        } catch (IOException e) {
            throw new HuaweiDataException(fileName + ": 读取 Excel 失败 -> " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new HuaweiDataException(fileName + ": 解析 Excel 失败 -> " + e.getMessage(), e);
        }
    }

    private ExcelSheet parseSheet(Sheet sheet) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 1) {
            return null;
        }
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        int columnCount = Math.max(sheet.getRow(lastRow) == null ? 0 : sheet.getRow(lastRow).getLastCellNum(),
                headerRow == null ? 0 : headerRow.getLastCellNum());
        if (columnCount <= 0) {
            return null;
        }
        List<String> headers = headers(headerRow, columnCount);

        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> values = values(row, columnCount);
            if (values.stream().allMatch(String::isEmpty)) {
                continue;
            }
            rows.add(values);
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new ExcelSheet(sheet.getSheetName(), headers, List.copyOf(rows));
    }

    private static List<String> headers(Row headerRow, int columnCount) {
        List<String> headers = new ArrayList<>(columnCount);
        Set<String> used = new HashSet<>();
        for (int column = 0; column < columnCount; column++) {
            String base = clean(cellText(headerRow == null ? null : headerRow.getCell(column)));
            if (base.isEmpty()) {
                base = "column_" + (column + 1);
            }
            String name = base;
            int suffix = 2;
            while (!used.add(name)) {
                name = base + "#" + suffix++;
            }
            headers.add(name);
        }
        return List.copyOf(headers);
    }

    private static List<String> values(Row row, int columnCount) {
        List<String> values = new ArrayList<>(columnCount);
        for (int column = 0; column < columnCount; column++) {
            values.add(clean(cellText(row == null ? null : row.getCell(column))));
        }
        return values;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return FORMATTER.formatCellValue(cell);
    }

    /** 去掉 Excel 导出带出的 Tab 与外层引号，并还原被转义的双引号。 */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            // 外层引号里面通常还有前导 Tab，去掉引号后要再 strip 一次
            text = text.substring(1, text.length() - 1).replace("\"\"", "\"").strip();
        }
        return text;
    }
}
