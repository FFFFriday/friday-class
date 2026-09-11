package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * .pptx 转换：逐页抽取文字，并生成对应的简易网页幻灯片。
 *
 * <p>本项目限定「文字型课件」（见项目宪章：只用 DeepSeek 纯文本模型，图片课件需 OCR，
 * 违反单模型约束），因此这里只抽取文本与表格内容，不处理图片。
 */
@Component
public class PptxConverter {

    private static final Logger log = LoggerFactory.getLogger(PptxConverter.class);

    /** 单份课件的最大页数：超过直接拒绝，避免超大课件拖垮内存与产生上万条数据。 */
    private static final int MAX_SLIDES = 500;

    /** 单页文字上限：courseware_page.text_content 是 TEXT(64KB)，留出安全余量。 */
    private static final int MAX_TEXT_PER_SLIDE = 32_000;

    static {
        // 防 zip bomb 的参数集中在 PptxZipSecurity（幂等）。
        // 曾经在这里把 minInflateRatio 收紧到 0.1，结果把正常课件误判成 zip bomb——
        // 原因与实测数据见该类注释，改这个值之前务必先读。
        PptxZipSecurity.apply();
    }

    /** 一页的解析结果。 */
    public record Slide(int pageNo, String text) {
    }

    /**
     * 逐页抽取文字。
     *
     * @param pptxPath 已落盘的 .pptx 绝对路径
     * @return 按页码升序的页列表；没有文字的页也会保留（text 为空串），
     *         以保证页码与 PPT 实际页数一一对应——翻页同步依赖这个对应关系。
     */
    public List<Slide> extractSlides(Path pptxPath) {
        List<Slide> result = new ArrayList<>();

        try (InputStream in = Files.newInputStream(pptxPath);
             XMLSlideShow ppt = new XMLSlideShow(in)) {

            List<XSLFSlide> slides = ppt.getSlides();
            if (slides.size() > MAX_SLIDES) {
                throw new BusinessException("课件页数过多（" + slides.size() + " 页），最多支持 " + MAX_SLIDES + " 页");
            }

            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slides.get(i).getShapes()) {
                    collectText(shape, sb);
                }
                result.add(new Slide(i + 1, truncate(sb.toString().strip())));
            }
        } catch (IOException | RuntimeException ex) {
            log.error("pptx_parse_failed path={}", pptxPath, ex);
            throw new BusinessException("课件解析失败，请确认是有效的 .pptx 文件");
        }

        if (result.isEmpty()) {
            throw new BusinessException("课件中没有可解析的幻灯片");
        }
        return result;
    }

    /** 递归收集形状里的文字：文本框 / 表格 / 组合形状。 */
    private void collectText(XSLFShape shape, StringBuilder sb) {
        if (shape instanceof XSLFTextShape textShape) {
            appendLine(sb, textShape.getText());
        } else if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                StringBuilder rowText = new StringBuilder();
                for (XSLFTableCell cell : row.getCells()) {
                    String cellText = cell.getText();
                    if (cellText != null && !cellText.isBlank()) {
                        if (rowText.length() > 0) {
                            rowText.append(" | ");
                        }
                        rowText.append(cellText.strip());
                    }
                }
                appendLine(sb, rowText.toString());
            }
        } else if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                collectText(child, sb);
            }
        }
    }

    /**
     * 单页文字截断。用 codePointCount/offsetByCodePoints 而不是 substring，
     * 避免把 emoji 等代理对从中间截断、产生非法 UTF-16 写入数据库。
     */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_PER_SLIDE) {
            return text;
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_TEXT_PER_SLIDE) {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_TEXT_PER_SLIDE);
        log.warn("slide_text_truncated originalCp={}", codePoints);
        return text.substring(0, end);
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.strip());
        }
    }

    /**
     * 生成该页的网页幻灯片 HTML。
     *
     * <p>注意：文字来自用户上传的课件，属于不可信内容，必须做 HTML 转义，
     * 否则课件里写一段脚本就会在展示时被执行（存储型 XSS）。
     */
    public String toSlideHtml(int pageNo, String text) {
        String safeText = escapeHtml(text).replace("\n", "<br>");
        String safePageNo = String.valueOf(pageNo);

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>第 " + safePageNo + " 页</title>\n"
                + "<style>\n"
                + "  html,body{margin:0;height:100%;background:#f0f0f2;"
                + "font-family:'Microsoft YaHei',-apple-system,sans-serif;}\n"
                + "  .slide{box-sizing:border-box;width:100%;min-height:100%;padding:6vh 8vw;"
                + "background:#fff;display:flex;flex-direction:column;}\n"
                + "  .page-no{color:#d97757;font-size:14px;margin-bottom:2vh;}\n"
                + "  .content{flex:1;font-size:clamp(16px,2.4vw,28px);line-height:1.8;"
                + "color:#222;white-space:normal;}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"slide\">\n"
                + "  <div class=\"page-no\">第 " + safePageNo + " 页</div>\n"
                + "  <div class=\"content\">" + safeText + "</div>\n"
                + "</div>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
