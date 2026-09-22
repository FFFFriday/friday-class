package com.fridayclass.service.agent.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 的轻量解析工具 —— 只需支持<b>模型会产出的那一小部分语法</b>。
 *
 * <h3>为什么自己写而不引一个 Markdown 库</h3>
 *
 * 需要的能力只有两件事：<b>去掉行内标记</b>与<b>把行归类</b>（标题 / 表格 / 列表 / 正文）。
 * 引一个完整解析器（commonmark 之类）要多一个依赖、多一套模型，
 * 而它解析出来的 AST 我们并不需要 —— 转换器要的是「这一行是几级标题」。
 *
 * <p>更要紧的是：模型产出的 Markdown <b>经常不完全合法</b>（表格少一列、标题后没空格）。
 * 一个严格的解析器会因此抛异常或整段吞掉，而下面的实现是<b>逐行尽力而为</b>，
 * 最坏情况只是把某一行当正文，不会让整个文件生成失败。
 */
public final class MarkdownText {

    /** 行内代码 `` `x` `` */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    /** 图片 ![alt](url) —— 放在链接之前处理，否则会先被链接规则吃掉前半截 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    /** 链接 [text](url) */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    /** 加粗/斜体 **x** *x* __x__ _x_ */
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__)(.+?)\\1|(\\*|_)(.+?)\\3");
    /** 引用 > */
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?");
    /** 无序列表 - * + */
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+");
    /** 有序列表 1. */
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+");
    /** 分割线 */
    private static final Pattern HRULE = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");
    /** ATX 标题 #{1,6} */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");
    /** 表格分隔行 |---|---| */
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private MarkdownText() {
    }

    /**
     * 去掉行内标记，留下可读纯文本。
     *
     * <p>顺序有讲究：图片必须先于链接处理 —— {@code ![a](b)} 里也含 {@code [a](b)}，
     * 先跑链接规则会把 {@code !} 剩下来变成「!a」。
     */
    public static String stripInline(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String s = IMAGE.matcher(line).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        s = replaceEmphasis(s);
        s = s.replace("\\", "");          // 转义符：Markdown 的 \" 之类
        return s.strip();
    }

    private static String replaceEmphasis(String s) {
        Matcher m = EMPHASIS.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            // 两种分支：**x** 用 group(2)，*x* 用 group(4)
            String inner = m.group(2) != null ? m.group(2) : m.group(4);
            m.appendReplacement(out, Matcher.quoteReplacement(inner == null ? "" : inner));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 拆一行表格 {@code | a | b |} → ["a", "b"]；不是表格行则返回空列表。 */
    public static List<String> splitTableRow(String line) {
        if (line == null || !line.contains("|")) {
            return List.of();
        }
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // 不按未转义的 \| 拆（模型极少用），直接按 | 拆后再 strip
        String[] cells = trimmed.split("\\|", -1);
        List<String> result = new ArrayList<>(cells.length);
        for (String cell : cells) {
            result.add(stripInline(cell));
        }
        return result;
    }

    /** 是不是表格的分隔行（{@code |---|---|}）。 */
    public static boolean isTableDivider(String line) {
        return line != null && line.contains("-") && TABLE_DIVIDER.matcher(line).matches();
    }

    /** 是不是表格行（含 |）。 */
    public static boolean isTableRow(String line) {
        return line != null && line.strip().contains("|");
    }

    /** 是不是分割线。 */
    public static boolean isHorizontalRule(String line) {
        return line != null && HRULE.matcher(line).matches();
    }

    /** 取标题层级；不是标题返回 0。 */
    public static int headingLevel(String line) {
        if (line == null) {
            return 0;
        }
        Matcher m = HEADING.matcher(line.strip());
        return m.matches() ? m.group(1).length() : 0;
    }

    /** 取标题文字（不含 #）。不是标题返回 null。 */
    public static String headingText(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = HEADING.matcher(line.strip());
        return m.matches() ? stripInline(m.group(2)) : null;
    }

    /** 引用行的内文。不是引用返回 null。 */
    public static String quoteText(String line) {
        if (line == null || !QUOTE.matcher(line).find()) {
            return null;
        }
        return stripInline(QUOTE.matcher(line).replaceFirst(""));
    }

    /** 是不是无序列表项。 */
    public static boolean isBullet(String line) {
        return line != null && BULLET.matcher(line).find();
    }

    /** 是不是有序列表项。 */
    public static boolean isOrdered(String line) {
        return line != null && ORDERED.matcher(line).find();
    }

    /** 列表项内文（去掉标记）。 */
    public static String listItemText(String line) {
        if (line == null) {
            return "";
        }
        return stripInline(ORDERED.matcher(BULLET.matcher(line).replaceFirst("")).replaceFirst(""));
    }

}
