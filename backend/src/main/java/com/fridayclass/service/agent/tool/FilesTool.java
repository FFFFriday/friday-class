package com.fridayclass.service.agent.tool;

import com.fridayclass.entity.AiGeneratedFile;
import com.fridayclass.repository.AiGeneratedFileRepository;
import com.fridayclass.service.agent.AgentToolContext;
import com.fridayclass.service.agent.WorkspaceFileService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 工具 4：列出 / 读回<b>自己生成过</b>的文件。
 *
 * <h2>为什么「列」和「读」合成一个工具</h2>
 *
 * 两者的差别只在于「传不传文件名」，拆成两个会让工具总数从 4 变成 5，
 * 而模型在两者之间选错的代价（该列的时候读了、该读的时候列了）
 * 比多一个描述段落的收益大得多。合成一个之后，
 * <b>「不传文件名 = 列出来看看」是模型很容易理解的约定</b>。
 *
 * <h2>读取范围的边界</h2>
 *
 * 只能读到<b>自己生成</b>的文件（Friday 拍板的「C」）。
 * 读不到别人的，读不到任何人的课件原文件 —— 课件内容走
 * {@link GetCoursewareContentTool}，那条路只回课件正文与知识点，
 * 不暴露磁盘路径。
 */
@Component
public class FilesTool implements AgentTool {

    private static final int MAX_LIST = 50;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AiGeneratedFileRepository fileRepository;
    private final WorkspaceFileService fileService;

    public FilesTool(AiGeneratedFileRepository fileRepository, WorkspaceFileService fileService) {
        this.fileRepository = fileRepository;
        this.fileService = fileService;
    }

    @Override
    public String name() {
        return "files";
    }

    @Override
    public String description() {
        return """
                查看或读取你之前为这位老师生成过的文件。
                不传 filename 时，列出已有的文件和它们所在的文件夹 ——
                老师提到「刚才那份」「上一份资料」时，先用它确认文件名。
                传了 filename 时，返回那个文件的正文（仅限 Markdown 与文本格式；
                Word / Excel 是二进制，读不出文字，只会告诉你它在哪）。
                只能看到自己生成的文件，看不到别人的，也看不到课件原始文件。""";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of(
                "filename", ToolSchemas.string("可选。要读取的文件名（含扩展名）。不填则只列出文件清单。")),
                null);
    }

    @Override
    public String execute(AgentToolContext context, Map<String, Object> args) {
        String filename = ToolArgs.string(args, "filename");
        if (filename != null) {
            context.report("正在读取《" + filename + "》…");
            return fileService.readMine(context.userId(), filename);
        }

        context.report("正在查看已有的文件…");
        List<AiGeneratedFile> files = fileRepository.findByOwnerIdOrderByUpdatedAtDescIdDesc(
                context.userId(), PageRequest.of(0, MAX_LIST));
        if (files.isEmpty()) {
            return "这位老师还没有生成过任何文件。";
        }
        StringBuilder out = new StringBuilder("已生成的文件（新→旧）：\n");
        for (AiGeneratedFile file : files) {
            out.append("- ").append(file.getFilename())
                    .append("（在「").append(file.getFolder()).append("」文件夹，")
                    .append(file.getFormat().name());
            if (file.getCreatedAt() != null) {
                out.append("，").append(file.getCreatedAt().format(TIME));
            }
            out.append("）\n");
        }
        if (files.size() == MAX_LIST) {
            out.append("（只列出最近 ").append(MAX_LIST).append(" 个）\n");
        }
        return out.toString();
    }
}
