package org.example.sporthealth.importer.huawei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;

class ExcelWorkbookParserTest {

    private final ExcelWorkbookParser parser = new ExcelWorkbookParser();

    @Test
    void cleansTabAndOuterQuotesAndDeduplicatesHeaders() throws IOException {
        byte[] workbook = workbook(wb -> {
            Sheet sheet = wb.createSheet("user run level data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("recordDay");
            header.createCell(1).setCellValue("version");
            header.createCell(2).setCellValue("version");
            header.createCell(3).setCellValue("");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("20261110");
            data.createCell(1).setCellValue("\"\t[{ \"\"a\"\": 1 }]\"");
            data.createCell(2).setCellValue("42");
            data.createCell(3).setCellValue("keep");

            // 整行空白应被跳过
            sheet.createRow(2);
        });

        List<ExcelSheet> sheets = parser.parse("user run level data.xls", new ByteArrayInputStream(workbook));

        assertEquals(1, sheets.size());
        ExcelSheet sheet = sheets.get(0);
        assertEquals("user run level data", sheet.name());
        assertEquals(List.of("recordDay", "version", "version#2", "column_4"), sheet.headers());
        assertEquals(1, sheet.rows().size());
        assertEquals(List.of("20261110", "[{ \"a\": 1 }]", "42", "keep"), sheet.rows().get(0));
    }

    @Test
    void skipsHeaderOnlyAndEmptySheets() throws IOException {
        byte[] workbook = workbook(wb -> {
            wb.createSheet("empty");
            Sheet headerOnly = wb.createSheet("header only");
            headerOnly.createRow(0).createCell(0).setCellValue("recordDay");
        });

        assertTrue(parser.parse("empty.xls", new ByteArrayInputStream(workbook)).isEmpty());
    }

    private interface WorkbookBuilder {
        void build(HSSFWorkbook workbook);
    }

    private byte[] workbook(WorkbookBuilder builder) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
