package com.fridayclass.service.agent.export;

import com.fridayclass.common.BusinessException;
import com.fridayclass.enums.FileFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → Excel（POI XSSF）：<b>只取 Markdown 里的表格</b>。
 *
 * <h2>为什么只取表格</h2>
 *
 * Excel 的强项是「一行行、一列列能被筛选排序的数据」，而不是整篇文章。
 * 把段落也塞进去会得到一列长长的文字，既不能排序也不能做透视 ——
 * 那种内容本来就应该用 Word 或文本。
 *
 * <p>这也让老师的选择变得有意义：要读的材料选 Word，
 * 要拉数据/发给学生自己统计的选 Excel。两种格式各干各擅长的事。
 *
 * <h2>没有表格时怎么办</h2>
 *
 * <b>不生成空文件，而是生成一张写清楚原因的「说明」表。</b>
 * 空文件对老师毫无用处 —— 打开是白的，他只会以为导出坏了、反复重试；
 * 而写着「本次内容里没有表格」的文件<b>自己解释了原因</b>，
 * 老师看完就知道「那我换个说法再让它做一份带表格的」。
 *
 * <p>同一句话也会作为工具结果回给模型，让它在对话里主动说明 ——
 * 双保险，因为老师不一定会去打开那个文件。
 */
@Component
public class XlsxExporter implements MarkdownExporter {

    private static final Logger log = LoggerFactory.getLogger(XlsxExporter.class);

    /**
     * sheet 名。刻意用「表格1、表格2」而<b>不</b>用最近的标题：
     * 标题可能含 {@code [ ] : * ? / \}（Excel 禁止出现在 sheet 名里）、可能超过 31 字、
     * 还可能两个表格挂在同一个标题下导致重名 —— 三种都会让 POI 抛异常。
     * 编号不会。
     */
    private static final String SHEET_PREFIX = "表格";
    private static final String NOTICE_SHEET = "说明";

    /** Excel 单元格文本上限 32767，留点余量。超长会当场抛异常而不是截断。 */
    private static final int MAX_CELL_CHARS = 32000;

    private static final String FONT_NAME = "宋体";
    private static final short FONT_PT = 11;

    @Override
    public FileFormat format() {
        return FileFormat.XLSX;
    }

    @Override
    public byte[] export(String markdown, String title) {
        List<MdBlock.Table> tables = new ArrayList<>();
        for (MdBlock block : MarkdownParser.parse(markdown)) {
            if (block instanceof MdBlock.Table table) {
                tables.add(table);
            }
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            if (tables.isEmpty()) {
                buildNoticeSheet(workbook, title);
            } else {
                for (int i = 0; i < tables.size(); i++) {
                    buildTableSheet(workbook, tables.get(i), i + 1);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.error("xlsx_export_failed", ex);
            throw new BusinessException(500, "Excel 文件生成失败，请稍后重试");
        }
    }

    private void buildTableSheet(XSSFWorkbook workbook, MdBlock.Table table, int index) {
        Sheet sheet = workbook.createSheet(SHEET_PREFIX + index);
        CellStyle headerStyle = createStyle(workbook, true);
        CellStyle bodyStyle = createStyle(workbook, false);

        int rowIndex = 0;
        if (table.hasHeader()) {
            writeRow(sheet.createRow(rowIndex++), table.header(), headerStyle);
        }
        for (List<String> row : table.rows()) {
            writeRow(sheet.createRow(rowIndex++), row, bodyStyle);
        }

        autoSize(sheet, table);
    }

    /**
     * 自动列宽。
     *
     * <p>⚠ POI 的 {@code autoSizeColumn} 在<b>没有图形环境</b>的服务器上会抛
     * {@code HeadlessException}（它要靠 AWT 量文字宽度）。本地开发看不出来，
     * 一部署就炸。所以这里自己按字符数估一个宽度。
     *
     * <p>估算规则：中文按 2 个字符宽算，其余按 1；上下留 2 格余量，封顶 60。
     */
    private void autoSize(Sheet sheet, MdBlock.Table table) {
        int columns = table.columnCount();
        for (int c = 0; c < columns; c++) {
            int widest = 8;
            if (table.hasHeader() && c < table.header().size()) {
                widest = Math.max(widest, displayWidth(table.header().get(c)));
            }
            for (List<String> row : table.rows()) {
                if (c < row.size()) {
                    widest = Math.max(widest, displayWidth(row.get(c)));
                }
            }
            sheet.setColumnWidth(c, Math.min(widest + 2, 60) * 256);
        }
    }

    private int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += (text.charAt(i) > 0x2E80) ? 2 : 1;
        }
        return width;
    }

    private void writeRow(Row row, List<String> cells, CellStyle style) {
        if (cells == null) {
            return;
        }
        for (int c = 0; c < cells.size(); c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(truncate(cells.get(c)));
            cell.setCellStyle(style);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS) + "…";
    }

    private CellStyle createStyle(XSSFWorkbook workbook, boolean bold) {
        Font font = workbook.createFont();
        font.setFontName(FONT_NAME);
        font.setFontHeightInPoints(FONT_PT);
        font.setBold(bold);

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setWrapText(false);
        return style;
    }

    private void buildNoticeSheet(XSSFWorkbook workbook, String title) {
        Sheet sheet = workbook.createSheet(NOTICE_SHEET);
        CellStyle boldStyle = createStyle(workbook, true);
        CellStyle bodyStyle = createStyle(workbook, false);

        Row first = sheet.createRow(0);
        Cell titleCell = first.createCell(0);
        titleCell.setCellValue(truncate(title == null || title.isBlank() ? "AI 生成内容" : title));
        titleCell.setCellStyle(boldStyle);

        Row second = sheet.createRow(1);
        Cell messageCell = second.createCell(0);
        messageCell.setCellValue("本次生成的内容里没有表格，因此没有可放进 Excel 的数据。"
                + "如果想得到一份表格，可以再让它「用表格的形式列出……」重新生成一次。");
        messageCell.setCellStyle(bodyStyle);

        sheet.setColumnWidth(0, 90 * 256);
    }
}
