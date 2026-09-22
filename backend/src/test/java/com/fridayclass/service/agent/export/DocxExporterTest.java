package com.fridayclass.service.agent.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word 导出器的规格验证。
 *
 * <h3>为什么断言的是「解压后的 XML」而不是「POI 读回来的属性」</h3>
 *
 * 两个原因，第二个是决定性的：
 *
 * <ol>
 *   <li>用 POI 读回来再断言，等于用同一套实现自证 —— POI 写错了、
 *       或者我们压根没设那个属性，读回来的值可能一样是默认值；</li>
 *   <li><b>真正的判据是 Word 打开时看到什么</b>，而 Word 只看 XML。
 *       字体是否设了 {@code eastAsia}、字号是几半点、行距是不是 360，
 *       全都直接写在 {@code word/document.xml} 里。查这里等于直接查了真相。</li>
 * </ol>
 *
 * <p>这个测试还有一层现实意义：<b>字体设错是肉眼看不出来的</b>。
 * 生成的文档在浏览器预览里一切正常，只有用 Word 打开、把光标点进中文里
 * 看字体框才发现不对。所以验收方式必须是读 XML，不能靠截图。
 */
class DocxExporterTest {

    private final DocxExporter exporter = new DocxExporter();

    private static final String SAMPLE = """
            # 本节复习资料

            ## 一、核心概念

            本节课讲了三个要点，分别是概念、方法与练习。

            - 第一个要点
            - 第二个要点

            ## 二、对照表

            | 名称 | 说明 |
            |---|---|
            | 概念 | 定义 |
            | 方法 | 步骤 |
            """;

    @Test
    @DisplayName("字体规格：正文宋体五号、一级标题小三、二级标题小四、行距 1.5")
    void matchesFontSpec() throws IOException {
        String xml = documentXml(exporter.export(SAMPLE, "本节复习资料"));

        // Word 用「半磅」存字号：10.5pt → 21，15pt → 30，12pt → 24
        assertContains(xml, "w:sz w:val=\"21\"", "正文应当是 10.5pt（五号）");
        assertContains(xml, "w:sz w:val=\"30\"", "一级标题应当是 15pt（小三）");
        assertContains(xml, "w:sz w:val=\"24\"", "二级标题应当是 12pt（小四）");

        // 行距 1.5：Word 存的是 240 × 倍数 = 360，规则 auto
        assertContains(xml, "w:line=\"360\"", "行距应当是 1.5 倍");
    }

    @Test
    @DisplayName("中文字体必须显式写进 eastAsia —— 只写 ascii 时 Word 会落回默认字体")
    void setsEastAsiaFont() throws IOException {
        String xml = documentXml(exporter.export(SAMPLE, "本节复习资料"));

        assertContains(xml, "w:eastAsia=\"" + DocxExporter.FONT_NAME + "\"",
                "中文必须落在 w:eastAsia 上；只设 w:ascii 时中文会用 Word 的默认字体");
        assertContains(xml, "w:ascii=\"" + DocxExporter.FONT_NAME + "\"",
                "西文也应当同字体，否则中英混排会出现两种字形");
    }

    @Test
    @DisplayName("Markdown 表格渲染成真正的 Word 表格（不是空格连起来的一行）")
    void rendersRealTable() throws IOException {
        byte[] bytes = exporter.export(SAMPLE, "本节复习资料");
        String xml = documentXml(bytes);

        assertContains(xml, "<w:tbl>", "表格应当变成 Word 的原生表格");
        assertTrue(xml.contains("名称") && xml.contains("说明"), "表头文字应当保留");

        // 再从 POI 侧确认结构：2 列、3 行（表头 + 2 数据行）
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertFalse(doc.getTables().isEmpty(), "应当至少有一张表");
            assertEquals(2, doc.getTables().get(0).getRows().get(0).getTableCells().size(),
                    "表格应当是 2 列");
            assertEquals(3, doc.getTables().get(0).getRows().size(), "应当是表头 + 2 行数据");
        }
    }

    @Test
    @DisplayName("模型没写一级标题时，用文件名兜底当标题")
    void usesFallbackTitle() throws IOException {
        byte[] bytes = exporter.export("## 只有二级标题\n\n正文。\n", "我的复习资料");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals("我的复习资料", firstParagraphText(doc), "第一段应当是兜底标题");
        }
    }

    @Test
    @DisplayName("已经写了一级标题时不再重复插入兜底标题")
    void doesNotDuplicateTitle() throws IOException {
        byte[] bytes = exporter.export("# 这是我自己写的标题\n\n正文。\n", "文件名");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals("这是我自己写的标题", firstParagraphText(doc));
        }
    }

    @Test
    @DisplayName("空内容不抛异常，产出仍然是一个能打开的文件")
    void handlesEmptyContent() throws IOException {
        byte[] bytes = exporter.export("", "空文档");
        assertNotNull(bytes);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertNotNull(doc.getBodyElements());
        }
    }

    @Test
    @DisplayName("Markdown 的行内标记（**加粗**、[链接](url)）会被清掉，不留符号")
    void stripsInlineMarkup() throws IOException {
        byte[] bytes = exporter.export("正文里有 **加粗** 和 [链接](http://example.com)。\n", "测试");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + b);
            assertFalse(text.contains("**"), "不应残留星号");
            assertFalse(text.contains("http://"), "链接地址不应出现在正文里");
            assertTrue(text.contains("加粗") && text.contains("链接"), "文字本身要保留");
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private static String firstParagraphText(XWPFDocument doc) {
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            if (!paragraph.getText().isBlank()) {
                return paragraph.getText();
            }
        }
        return "";
    }

    /** 从 docx 字节里取出 {@code word/document.xml} 的原文。 */
    private static String documentXml(byte[] docx) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("生成的 docx 里没有 word/document.xml，文件结构不对");
    }

    private static void assertContains(String haystack, String needle, String why) {
        assertTrue(haystack.contains(needle),
                "XML 里找不到 " + needle + " —— " + why
                        + "\n（实际内容片段：" + snippet(haystack, needle) + "）");
    }

    private static String snippet(String xml, String needle) {
        int index = xml.indexOf(needle.substring(0, Math.min(4, needle.length())));
        if (index < 0) {
            return xml.substring(0, Math.min(400, xml.length()));
        }
        int from = Math.max(0, index - 80);
        return xml.substring(from, Math.min(xml.length(), index + 120));
    }
}
