package com.fridayclass.service.agent.export;

import com.fridayclass.enums.FileFormat;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Markdown 原样输出。
 *
 * <p>这是「模型产出什么就存什么」的一条通道：不做任何加工，
 * 因此也是唯一一种<b>内容能 100% 对齐模型输出</b>的格式。
 * 调试转换器时，先看 .md 就能确定「是对不上还是转错了」。
 */
@Component
public class MarkdownPassthroughExporter implements MarkdownExporter {

    @Override
    public FileFormat format() {
        return FileFormat.MD;
    }

    @Override
    public byte[] export(String markdown, String title) {
        return (markdown == null ? "" : markdown).getBytes(StandardCharsets.UTF_8);
    }
}
