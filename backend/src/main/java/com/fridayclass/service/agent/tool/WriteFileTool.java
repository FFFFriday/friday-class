package com.fridayclass.service.agent.tool;

import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.AiGeneratedFile;
import com.fridayclass.service.agent.AgentToolContext;
import com.fridayclass.service.agent.WorkspaceFileService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具 3：写一个文件。
 *
 * <h2>模型决定「叫什么、写什么」，服务端决定「写到哪、写成什么格式」</h2>
 *
 * 参数表里只有 {@code filename} 与 {@code content}：
 * <ul>
 *   <li><b>没有目录</b> —— 目录由老师在界面上选定，经 {@code AgentToolContext} 注入。
 *       模型无法表达「写到别的文件夹」这个意图（见 {@code AgentTool} 的类注释）；</li>
 *   <li><b>没有格式</b> —— 老师选的是 Word，出来的就是 Word。
 *       模型给的文件名带了什么扩展名都会被改写（{@code .md} → {@code .docx}）。</li>
 * </ul>
 *
 * <h2>为什么正文要求是 Markdown</h2>
 *
 * 转成 Word / Excel 是后端的确定性代码。让模型直接产出 docx 不现实，
 * 而让它产出 Markdown 是它最擅长的事 —— 这一步的取舍见 {@code MarkdownExporter}。
 */
@Component
public class WriteFileTool implements AgentTool {

    private final WorkspaceFileService fileService;

    public WriteFileTool(WorkspaceFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return """
                把一份内容写成文件，保存到老师指定的文件夹里，老师随后可以下载。
                要产出任何文件都必须调用它 —— 只把内容写在回复里，老师是拿不到文件的。
                正文一律用 Markdown 写（# 表示标题、- 表示列表、| 表示表格）；
                实际格式由老师在界面上选的输出格式决定，不需要你操心文件扩展名。
                文件名写清楚内容即可，例如「本节复习资料」。
                内容要完整、可以直接给老师使用，不要写「此处省略」之类的话。""";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
                Map.of(
                        "filename", ToolSchemas.string("文件名，不必带扩展名。例如：本节复习资料"),
                        "content", ToolSchemas.string("文件的完整正文，用 Markdown 书写。")),
                List.of("filename", "content"));
    }

    @Override
    public String execute(AgentToolContext context, Map<String, Object> args) {
        String filename = ToolArgs.string(args, "filename");
        if (filename == null) {
            throw new BusinessException(400, "filename 不能为空");
        }
        String content = ToolArgs.text(args, "content");
        if (content == null) {
            throw new BusinessException(400, "content 不能为空，文件正文必须完整给出");
        }

        context.report("正在生成《" + filename + "》…");

        AiGeneratedFile written = fileService.writeFromMarkdown(
                context.userId(), context.fileContext(), filename, content);

        return String.format(
                "已生成《%s》，保存在「%s」文件夹里（%s 格式，%s）。老师可以在页面下方「生成的文件」中下载。",
                written.getFilename(), written.getFolder(),
                written.getFormat().name(), humanSize(written.getSizeBytes()));
    }

    private static String humanSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "大小未知";
        }
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
