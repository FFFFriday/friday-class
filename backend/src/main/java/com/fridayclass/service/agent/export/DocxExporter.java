package com.fridayclass.service.agent.export;

import com.fridayclass.common.BusinessException;
import com.fridayclass.enums.FileFormat;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Markdown → Word（POI XWPF）。
 *
 * <h2>字体规格（Friday 2026-09-23 拍板，见「修改文档2」决策 15）</h2>
 * <table border="1">
 *   <tr><th>元素</th><th>字体</th><th>字号</th><th>pt</th></tr>
 *   <tr><td>正文</td><td>宋体</td><td>五号</td><td>10.5</td></tr>
 *   <tr><td>一级标题</td><td>宋体</td><td>小三</td><td>15</td></tr>
 *   <tr><td>二级标题</td><td>宋体</td><td>小四</td><td>12</td></tr>
 * </table>
 * 行距统一 <b>1.5</b>（表格单元格内为 1.0，否则表格会被撑得很高）。
 *
 * <h2>⚠ 为什么必须同时设 ascii 与 eastAsia</h2>
 *
 * Word 的字体是按「字符区间」分别指定的：西文走 {@code w:ascii} / {@code w:hAnsi}，
 * 中文走 {@code w:eastAsia}。<b>只设 ascii 时，中文会落回 Word 自己的默认字体</b>
 * （通常是等线或宋体之外的某个字体）。
 *
 * <p>这个坑最要命的地方是<b>肉眼当场看不出来</b>：生成的文档在浏览器预览里
 * 一切正常，只有用 Word 打开、把光标点进中文里看字体框才发现不对。
 * 所以验收方式是<b>解压 docx 读 XML</b>（见 A2 §十三），不是截图看一眼。
 *
 * <p>另外，{@link #applyChineseFont} 对<b>每一个 run</b> 都独立设置，
 * 不依赖文档级默认样式 —— 默认样式在「别人拿到文件后 Ctrl+A 换主题」时容易失效，
 * 而逐 run 设置是写在内容里的，挪到哪里都还在。
 */
@Component
public class DocxExporter implements MarkdownExporter {

    private static final Logger log = LoggerFactory.getLogger(DocxExporter.class);

    /** 中文正文与标题统一用宋体（决策 15；与宪章 §七 的「标题黑体」有意不同）。 */
    public static final String FONT_NAME = "宋体";

    private static final double BODY_PT = 10.5;
    private static final double H1_PT = 15.0;
    private static final double H2_PT = 12.0;

    private static final double BODY_LINE_SPACING = 1.5;
    /** 表格单元格里用单倍行距：1.5 会把每行撑高一倍，一张 10 行的表就占满一页。 */
    private static final double TABLE_LINE_SPACING = 1.0;

    /** Excel 与 Word 都有单元格/段落长度上限，超长会被静默截断，这里提前拦住。 */
    private static final int MAX_CELL_CHARS = 8000;

    @Override
    public FileFormat format() {
        return FileFormat.DOCX;
    }

    @Override
    public byte[] export(String markdown, String title) {
        List<MdBlock> blocks = MarkdownParser.parse(markdown);
        try (XWPFDocument document = new XWPFDocument()) {
            appendFallbackTitleIfNeeded(document, blocks, title);
            for (MdBlock block : blocks) {
                append(document, block);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.error("docx_export_failed", ex);
            throw new BusinessException(500, "Word 文档生成失败，请稍后重试");
        }
    }

    /**
     * 模型没写一级标题时，用文件名当文档标题。
     *
     * <p>导出后打开是一张没有抬头的纸，比多一行标题难看得多；
     * 而文件名恰好就是老师刚才起的那个名字，用它当标题最自然。
     */
    private void appendFallbackTitleIfNeeded(XWPFDocument document, List<MdBlock> blocks, String title) {
        boolean hasTitle = blocks.stream()
                .anyMatch(b -> b instanceof MdBlock.Heading heading && heading.level() == 1);
        if (!hasTitle && title != null && !title.isBlank()) {
            appendHeading(document, 1, title.strip());
        }
    }

    private void append(XWPFDocument document, MdBlock block) {
        switch (block) {
            case MdBlock.Heading heading -> appendHeading(document, heading.level(), heading.text());
            case MdBlock.Paragraph paragraph -> appendBody(document, paragraph.text(), false);
            case MdBlock.Bullet bullet -> appendBody(document, "· " + bullet.text(), false);
            case MdBlock.Ordered ordered -> appendBody(document, ordered.text(), false);
            case MdBlock.Quote quote -> appendBody(document, "「" + quote.text() + "」", false);
            case MdBlock.Rule ignored -> appendBody(document, "──────────", false);
            case MdBlock.Table table -> appendTable(document, table);
        }
    }

    /**
     * 标题一/二/三级分别用 15 / 12 / 12pt。
     *
     * <p>规格只定义了两级。三级及以下并入二级（都是 12pt 加粗）——
     * 而不是自己发明一个更小的字号：<b>发明出来的规格没人验证过</b>，
     * 而且老师看到的结果会和「拍板的那张表」对不上。
     */
    private void appendHeading(XWPFDocument document, int level, String text) {
        double size = (level <= 1) ? H1_PT : H2_PT;
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBetween(BODY_LINE_SPACING);
        styleRun(paragraph, text, size, true);
    }

    private void appendBody(XWPFDocument document, String text, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBetween(BODY_LINE_SPACING);
        styleRun(paragraph, text, BODY_PT, bold);
    }

    /** 表格渲染成<b>真正的 Word 表格</b>，而不是把单元格用空格连起来 —— 后者在 Word 里很难看。 */
    private void appendTable(XWPFDocument document, MdBlock.Table table) {
        int columns = table.columnCount();
        if (columns == 0) {
            return;
        }
        int totalRows = table.rows().size() + (table.hasHeader() ? 1 : 0);
        XWPFTable xwpfTable = document.createTable(totalRows, columns);

        int rowIndex = 0;
        if (table.hasHeader()) {
            fillRow(xwpfTable.getRow(rowIndex++), table.header(), columns, true);
        }
        for (List<String> row : table.rows()) {
            fillRow(xwpfTable.getRow(rowIndex++), row, columns, false);
        }

        // 表格后面补一个空段落：连续两个表格在 Word 里会粘成一张，
        // 而且表格紧贴下一个标题时分不清边界。
        document.createParagraph();
    }

    private void fillRow(XWPFTableRow row, List<String> cells, int columns, boolean bold) {
        for (int c = 0; c < columns; c++) {
            XWPFTableCell cell = row.getCell(c);
            String text = (cells != null && c < cells.size()) ? cells.get(c) : "";
            if (text != null && text.length() > MAX_CELL_CHARS) {
                text = text.substring(0, MAX_CELL_CHARS) + "…";
            }
            // 新建的单元格自带一个空段落，直接往它里面写，不再 createParagraph，
            // 否则每个格子里会多出一行空白。
            XWPFParagraph paragraph = cell.getParagraphs().get(0);
            paragraph.setSpacingBetween(TABLE_LINE_SPACING);
            styleRun(paragraph, text, BODY_PT, bold);
        }
    }

    private void styleRun(XWPFParagraph paragraph, String text, double sizePt, boolean bold) {
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontSize(sizePt);
        run.setBold(bold);
        applyChineseFont(run);
    }

    /**
     * 把西文与中文字体都钉死成宋体。
     *
     * <p>遍历 {@link XWPFRun.FontCharRange#values()} 而不是只写 ascii + eastAsia：
     * 四个区间（ascii / hAnsi / eastAsia / cs）分别对应不同的字符集，
     * 漏掉 {@code hAnsi} 时，某些标点会落回默认字体 —— 同样是肉眼难查的那一类问题。
     */
    private void applyChineseFont(XWPFRun run) {
        for (XWPFRun.FontCharRange range : XWPFRun.FontCharRange.values()) {
            run.setFontFamily(FONT_NAME, range);
        }
    }
}
