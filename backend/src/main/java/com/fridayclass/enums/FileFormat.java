package com.fridayclass.enums;

import com.fridayclass.common.BusinessException;

import java.util.Locale;

/**
 * AI 智能体产出的文件格式。
 *
 * <p><b>这里是格式白名单的唯一真源。</b> 前端下拉框、接口校验、转换器分发
 * 都从它取值 —— 散在三处的话，加一种格式一定会漏掉其中一处。
 *
 * <h3>为什么没有 PDF</h3>
 *
 * Friday 拍板只要这三种（加上 Markdown 共四种）。PDF 需要额外的排版引擎，
 * 而且生成出来的东西没法用 Word 再编辑 —— 老师要的是「能改的材料」，不是终稿。
 *
 * <h3>为什么模型不直接产出这些格式</h3>
 *
 * 模型只写 Markdown，格式由后端的确定性代码转（见 {@code export} 包）。
 * 让模型直接吐 docx 二进制是不现实的；而转换器是纯函数，可以单独写单测。
 */
public enum FileFormat {

    /** Markdown 原文。模型直接产出的就是它。 */
    MD("md"),

    /** 纯文本：去掉 Markdown 标记后的结果。 */
    TXT("txt"),

    /** Word 文档（POI XWPF）。 */
    DOCX("docx"),

    /** Excel 表格（POI XSSF）：只取 Markdown 里的表格。 */
    XLSX("xlsx");

    private final String extension;

    FileFormat(String extension) {
        this.extension = extension;
    }

    /** 小写扩展名，不带点。用于拼文件名与下载时的 Content-Type 判定。 */
    public String extension() {
        return extension;
    }

    /**
     * 从请求里的字符串解析格式（前端传 {@code "md"} / {@code "docx"} 这种小写值）。
     *
     * <p><b>校验放在这里而不是靠 {@code @Pattern}</b>：白名单只有枚举知道，
     * 写成注解的话，将来加一种格式就要记得同步改注解 —— 又是一处会漏的地方。
     *
     * @throws BusinessException 不在白名单内。4 开头的业务码而非 500：
     *                           这是调用方传了不支持的格式，改一下就好
     */
    public static FileFormat fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "请选择输出格式");
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (FileFormat format : values()) {
            if (format.name().equals(normalized)) {
                return format;
            }
        }
        throw new BusinessException(400, "不支持的输出格式：" + raw);
    }
}
