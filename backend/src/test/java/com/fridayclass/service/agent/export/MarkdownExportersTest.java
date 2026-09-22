package com.fridayclass.service.agent.export;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本与 Excel 两个导出器的用例。
 *
 * <p>关注点与 docx 那套不同：docx 验证的是<b>格式规格</b>（字体字号行距），
 * 这里验证的是<b>内容不被弄丢、也不被弄脏</b>——
 * 转换器最容易犯的错不是排版难看，而是悄悄吞掉一段内容。
 */
class MarkdownExportersTest {

    private static final String SAMPLE = """
            # 本节复习资料

            正文里有 **加粗**、`代码` 和 [链接](http://example.com)。

            ## 学生提问

            - 第一个要点
            - 第二个要点

            1. 第一步
            2. 第二步

            > 这是一段引用

            | 姓名 | 提问数 |
            |---|---|
            | 张三 | 3 |
            | 李四 | 5 |
            """;

    @Nested
    @DisplayName("Markdown 解析（三种转换器共用）")
    class 解析 {

        @Test
        @DisplayName("含竖线的列表行不会被误判成表格")
        void listLinesWithPipesStayBullets() {
            // 这是真实会发生的写法：「第 N 页：A | B」这种总结行连着出现两行。
            // 如果先判表格，它们会被当成一张两行两列的表 —— 列表符号消失、
            // 内容被塞进格子，选 Excel 时还会多出一张莫名其妙的表。
            var blocks = MarkdownParser.parse("- 第 1 页：A | B\n- 第 2 页：C | D\n");
            assertEquals(2, blocks.size(), "应当解析成两个块");
            assertTrue(blocks.get(0) instanceof MdBlock.Bullet,
                    "第一行应当是列表项，实际：" + blocks.get(0));
            assertTrue(blocks.get(1) instanceof MdBlock.Bullet,
                    "第二行应当是列表项，实际：" + blocks.get(1));
        }

        @Test
        @DisplayName("真正的表格仍然解析成表格，表头由分隔行确定")
        void realTableStillWorks() {
            var blocks = MarkdownParser.parse("| A | B |\n|---|---|\n| 1 | 2 |\n");
            assertEquals(1, blocks.size());
            var table = (MdBlock.Table) blocks.get(0);
            assertEquals(List.of("A", "B"), table.header(), "第一行应当是表头");
            assertEquals(1, table.rows().size(), "数据行应当只有一行");
        }

        @Test
        @DisplayName("孤零零一行含竖线的正文不当表格")
        void lonePipeLineIsParagraph() {
            var blocks = MarkdownParser.parse("支持 A | B 两种模式。\n");
            assertEquals(1, blocks.size());
            assertTrue(blocks.get(0) instanceof MdBlock.Paragraph,
                    "应当当正文，实际：" + blocks.get(0));
        }

        @Test
        @DisplayName("空内容不抛异常")
        void handlesEmpty() {
            assertTrue(MarkdownParser.parse(null).isEmpty());
            assertTrue(MarkdownParser.parse("").isEmpty());
            assertTrue(MarkdownParser.parse("   \n\n  ").isEmpty());
        }
    }

    @Nested
    @DisplayName("纯文本导出")
    class 纯文本 {

        private final PlainTextExporter exporter = new PlainTextExporter();

        @Test
        @DisplayName("去掉 Markdown 标记，但一个字的内容都不能少")
        void stripsMarkupButKeepsContent() {
            String text = new String(exporter.export(SAMPLE, "本节复习资料"), StandardCharsets.UTF_8);

            assertFalse(text.contains("#"), "标题符号应当去掉：" + text);
            assertFalse(text.contains("**"), "加粗符号应当去掉");
            assertFalse(text.contains("](http"), "链接地址应当去掉");
            assertFalse(text.contains("|---"), "表格分隔行应当整行去掉");

            for (String must : new String[]{"本节复习资料", "第一个要点", "第二个要点",
                    "第一步", "这是一段引用", "张三", "李四", "加粗", "代码"}) {
                assertTrue(text.contains(must), "内容「" + must + "」被弄丢了:\n" + text);
            }
        }

        @Test
        @DisplayName("表格转成用空格分隔的行，不留竖线")
        void rendersTableWithoutPipes() {
            String text = new String(exporter.export(SAMPLE, "t"), StandardCharsets.UTF_8);
            assertFalse(text.contains("|"), "纯文本里不该出现表格竖线");
            assertTrue(text.contains("张三"), "表格内容要保留");
        }

        @Test
        @DisplayName("空内容是空字符串，不抛异常")
        void handlesEmpty() {
            assertEquals("", new String(exporter.export("", "t"), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("Excel 导出")
    class Excel {

        private final XlsxExporter exporter = new XlsxExporter();

        @Test
        @DisplayName("一张表 → 一个 sheet，表头 + 2 行数据")
        void singleTable() throws IOException {
            byte[] bytes = exporter.export(SAMPLE, "本节复习资料");
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertEquals(1, workbook.getNumberOfSheets(), "示例里只有一张表");
                var sheet = workbook.getSheetAt(0);
                assertEquals("姓名", sheet.getRow(0).getCell(0).getStringCellValue(),
                        "第一行应当是表头");
                assertEquals("李四", sheet.getRow(2).getCell(0).getStringCellValue(),
                        "第三行应当是第二条数据");
                assertEquals(3, sheet.getLastRowNum() + 1, "应当是表头 + 2 行数据");
            }
        }

        @Test
        @DisplayName("多张表 → 多个 sheet，编号递增")
        void multipleTables() throws IOException {
            String markdown = """
                    | A | B |
                    |---|---|
                    | 1 | 2 |

                    中间夹一段正文。

                    | C | D |
                    |---|---|
                    | 3 | 4 |
                    """;
            byte[] bytes = exporter.export(markdown, "t");
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertEquals(2, workbook.getNumberOfSheets());
                assertEquals("表格1", workbook.getSheetName(0));
                assertEquals("表格2", workbook.getSheetName(1));
                assertEquals("C", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
            }
        }

        @Test
        @DisplayName("没有表格时给一张「说明」表，而不是空文件")
        void noTableGivesExplanationSheet() throws IOException {
            byte[] bytes = exporter.export("只有一段文字，没有任何表格。\n", "课程要点");
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertEquals(1, workbook.getNumberOfSheets());
                assertEquals("说明", workbook.getSheetName(0));
                String title = workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
                String message = workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue();
                assertEquals("课程要点", title);
                assertTrue(message.contains("没有表格"), "说明文字要讲清原因：" + message);
            }
        }

        @Test
        @DisplayName("不合法 Markdown（表格缺分隔行）不会被误判成表格")
        void malformedTableIsNotATable() throws IOException {
            // 单独一行含竖线的正文，不该被当成表格 —— 否则会出现只有一行一列的怪 sheet
            byte[] bytes = exporter.export("支持 A | B 两种模式。\n", "t");
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertEquals("说明", workbook.getSheetName(0));
            }
        }
    }
}
