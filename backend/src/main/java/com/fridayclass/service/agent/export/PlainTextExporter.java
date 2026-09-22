package com.fridayclass.service.agent.export;

import com.fridayclass.enums.FileFormat;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本输出：去掉 Markdown 标记。
 *
 * <p>去掉的是「渲染时才显示、在记事本里却占位置」的那些符号：
 * {@code #}、{@code **}、{@code []()}、表格的竖线。
 * 保留的是「本来就有意义」的结构：列表项换成 {@code ·}、引用包上「」。
 *
 * <p><b>表格用「单元格之间空两格」而不是原样保留竖线</b>：
 * 在记事本里 {@code | a | b |} 并不比 {@code a  b} 更好读，
 * 反而像文件坏了。要真正的表格就选 Excel。
 */
@Component
public class PlainTextExporter implements MarkdownExporter {

    private static final String NL = System.lineSeparator();

    @Override
    public FileFormat format() {
        return FileFormat.TXT;
    }

    @Override
    public byte[] export(String markdown, String title) {
        StringBuilder out = new StringBuilder();
        for (MdBlock block : MarkdownParser.parse(markdown)) {
            switch (block) {
                case MdBlock.Heading heading -> out.append(heading.text()).append(NL);
                case MdBlock.Paragraph paragraph -> out.append(paragraph.text()).append(NL);
                case MdBlock.Bullet bullet -> out.append("· ").append(bullet.text()).append(NL);
                case MdBlock.Ordered ordered -> out.append(ordered.text()).append(NL);
                case MdBlock.Quote quote -> out.append("「").append(quote.text()).append("」").append(NL);
                case MdBlock.Rule ignored -> out.append("──────────").append(NL);
                case MdBlock.Table table -> appendTable(out, table);
            }
        }
        return out.toString().strip().getBytes(StandardCharsets.UTF_8);
    }

    private void appendTable(StringBuilder out, MdBlock.Table table) {
        if (table.hasHeader()) {
            out.append(String.join("  ", table.header())).append(NL);
        }
        for (java.util.List<String> row : table.rows()) {
            out.append(String.join("  ", row)).append(NL);
        }
    }
}
