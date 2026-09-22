package com.fridayclass.service.agent.export;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型产出的 Markdown 切成 {@link MdBlock} 列表。
 *
 * <h3>为什么要容忍「不合法的 Markdown」</h3>
 *
 * 模型产出的东西经常差一点：表格少一列、标题后面忘了空格、列表缩进不一致。
 * 一个严格的解析器遇到这些会抛异常或整段吞掉，而老师看到的将是
 * 「文件是生成了，但里面少了一半内容」。
 *
 * <p>所以这里的原则是<b>逐行尽力而为</b>：认不出来的一律当普通段落，
 * 最坏结果是排版朴素一点，<b>绝不丢内容</b>。
 */
public final class MarkdownParser {

    /**
     * 单独一行含 {@code |} 的，只有当它属于一个「成形的表格」时才算表格。
     *
     * <p>否则「支持 A | B 两种模式」这种正文会被误判成表格，
     * 渲染成一行一列的格子。判据是：块内至少两行，或出现过分隔行。
     */
    private static final int MIN_TABLE_ROWS = 2;

    private MarkdownParser() {
    }

    public static List<MdBlock> parse(String markdown) {
        List<MdBlock> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }
        String[] lines = markdown.split("\r?\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }

            String heading = MarkdownText.headingText(line);
            if (heading != null) {
                blocks.add(new MdBlock.Heading(MarkdownText.headingLevel(line), heading));
                i++;
                continue;
            }
            if (MarkdownText.isHorizontalRule(line)) {
                blocks.add(new MdBlock.Rule());
                i++;
                continue;
            }
            // ⚠ 列表项的判断必须**排在表格之前**。
            //
            //   isTableRow 的判据只是「这一行里有竖线」，而列表项完全可能含竖线：
            //        - 第 1 页：A | B
            //        - 第 2 页：C | D
            //   两行连在一起就满足「连续两行含竖线」，于是被当成一张表 ——
            //   列表符号消失、内容被塞进格子，老师选 Excel 时还会多出一张莫名其妙的表。
            //
            //   先判列表就不会误判：以 `- ` / `1. ` 开头的行是列表，不是表格行。
            String quote = MarkdownText.quoteText(line);
            if (quote != null) {
                blocks.add(new MdBlock.Quote(quote));
                i++;
                continue;
            }
            if (MarkdownText.isOrdered(line)) {
                blocks.add(new MdBlock.Ordered(line.strip()));
                i++;
                continue;
            }
            if (MarkdownText.isBullet(line)) {
                blocks.add(new MdBlock.Bullet(MarkdownText.listItemText(line)));
                i++;
                continue;
            }
            if (MarkdownText.isTableRow(line)) {
                i = consumeTable(lines, i, blocks);
                continue;
            }
            blocks.add(new MdBlock.Paragraph(MarkdownText.stripInline(line)));
            i++;
        }
        return blocks;
    }

    /**
     * 从 {@code start} 起吃掉连续的表格行，返回下一行的下标。
     *
     * <p>分隔行（{@code |---|---|}）不产生单元格，但它出现意味着
     * <b>它上面那一行是表头</b> —— 这个信息不能丢，
     * 否则导出的 Excel 会把表头当普通数据行、加粗也就无从谈起。
     */
    private static int consumeTable(String[] lines, int start, List<MdBlock> blocks) {
        List<List<String>> body = new ArrayList<>();
        boolean sawDivider = false;
        int i = start;
        while (i < lines.length && MarkdownText.isTableRow(lines[i])) {
            if (MarkdownText.isTableDivider(lines[i])) {
                sawDivider = true;
            } else {
                body.add(MarkdownText.splitTableRow(lines[i]));
            }
            i++;
        }

        // 孤零零一行含竖线的正文，不是表格
        if (!sawDivider && body.size() < MIN_TABLE_ROWS) {
            for (List<String> row : body) {
                blocks.add(new MdBlock.Paragraph(String.join(" ", row)));
            }
            return i;
        }

        List<String> header = List.of();
        List<List<String>> rows = body;
        if (sawDivider && !body.isEmpty()) {
            header = body.get(0);
            rows = body.subList(1, body.size());
        }
        blocks.add(new MdBlock.Table(header, List.copyOf(rows)));
        return i;
    }
}
