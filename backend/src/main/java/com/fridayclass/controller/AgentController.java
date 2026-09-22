package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.CoursewareResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.agent.AgentFileResponse;
import com.fridayclass.dto.agent.AgentFolderRequest;
import com.fridayclass.dto.agent.AgentTaskRequest;
import com.fridayclass.dto.agent.AgentTaskResponse;
import com.fridayclass.enums.FileFormat;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.agent.AgentTaskService;
import com.fridayclass.service.agent.WorkspaceFileService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * AI 智能体接口。**仅教师与管理员**。
 *
 * <p>⚠ <b>必须在 {@code SecurityConfig} 里显式加白名单</b>：
 * 项目是 deny-by-default，漏了这一行每个接口都会 403，
 * 而症状是「功能不好用」，很容易被当成前端 bug 查半天。
 * 方法级 {@code @PreAuthorize} 只是纵深防御，真正的墙在 SecurityConfig。
 *
 * <p>⚠ <b>学生的 token 打这里必须返回 403</b> —— 这条要实测，不能靠推断。
 * 前端那条路由守卫只是体验层，绕过它只需要改一个 localStorage 值。
 */
@RestController
@RequestMapping("/api/agent")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AgentController {

    private final AgentTaskService taskService;
    private final WorkspaceFileService fileService;

    public AgentController(AgentTaskService taskService, WorkspaceFileService fileService) {
        this.taskService = taskService;
        this.fileService = fileService;
    }

    /** 发起任务。立刻返回 taskId，真正的活在后台跑。 */
    @PostMapping("/tasks")
    public ApiResponse<AgentTaskResponse> start(@Valid @RequestBody AgentTaskRequest request,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(taskService.start(principal.getId(), request));
    }

    /** 轮询任务状态 / 进度 / 结果。只返回自己的任务。 */
    @GetMapping("/tasks/{id}")
    public ApiResponse<AgentTaskResponse> status(@PathVariable Long id,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(taskService.status(principal.getId(), id));
    }

    /** 我的文件列表，可按课堂过滤。 */
    @GetMapping("/files")
    public ApiResponse<ListResult<AgentFileResponse>> files(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long sessionId) {
        return ApiResponse.ok(ListResult.of(taskService.listFiles(principal.getId(), sessionId)));
    }

    /**
     * 下载。
     *
     * <p><b>owner 校验在这里是硬要求</b>：文件 id 是连续自增的，
     * 少了这一步，把 url 里的 5 改成 6 就能下载别人的文档。
     * 校验由 {@code findByIdAndOwnerId} 完成（服务层），
     * 找不到与不是自己的一律 404，不泄漏「这个 id 存在」。
     */
    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        WorkspaceFileService.Download download = taskService.download(principal.getId(), id);

        // 文件名走 RFC 5987 编码：不这样处理的话，中文文件名到了浏览器里会变成乱码
        // 或者被截断成扩展名之前的一小段。
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.file().getFilename(), StandardCharsets.UTF_8)
                .build();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaTypeOf(download.file().getFormat()))
                // 这是「某个人的私人文档」，不该被任何中间层缓存下来。
                // 顺带关掉内容嗅探：产出物是老师可控的文本，浏览器不该去猜它的类型。
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff");

        // ⚠ 长度取不到时**不要**调 contentLength(-1)：那会把 Content-Length 头
        //   设成字面量 "-1"，是个非法值。取不到就不带这个头，让容器自己决定
        //   （通常退回 chunked），这才是原本想表达的意思。
        long size = sizeOf(download);
        if (size >= 0) {
            builder.contentLength(size);
        }
        return builder.body(new FileSystemResource(download.path()));
    }

    /** 我的文件夹列表。首次访问会自动建一个默认文件夹，避免下拉框是空的。 */
    @GetMapping("/folders")
    public ApiResponse<ListResult<String>> folders(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(fileService.listFolders(principal.getId())));
    }

    /** 新建文件夹。只接收<b>名字</b>，路径由服务端拼。 */
    @PostMapping("/folders")
    public ApiResponse<Map<String, String>> createFolder(@Valid @RequestBody AgentFolderRequest request,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        String name = fileService.createFolder(principal.getId(), request.name());
        return ApiResponse.ok(Map.of("name", name));
    }

    /**
     * 我上传的课件（下拉框）。
     *
     * <p>刻意不复用门户的 {@code GET /api/courseware}：那个接口是<b>公开</b>的，
     * 返回所有人上传的课件。用它当下拉框数据源，会出现
     * 「选项里能看到别人的课件，选中后被 403 挡回来」——老师只会以为功能坏了。
     */
    @GetMapping("/coursewares")
    public ApiResponse<ListResult<CoursewareResponse>> coursewares(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(taskService.myCoursewares(principal.getId())));
    }

    private static MediaType mediaTypeOf(FileFormat format) {
        if (format == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return switch (format) {
            case MD -> MediaType.parseMediaType("text/markdown; charset=UTF-8");
            case TXT -> MediaType.parseMediaType("text/plain; charset=UTF-8");
            case DOCX -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case XLSX -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
    }

    private static long sizeOf(WorkspaceFileService.Download download) {
        try {
            return java.nio.file.Files.size(download.path());
        } catch (Exception ex) {
            // 取不到长度就不带 Content-Length，交给容器自己算，不影响下载本身
            return -1L;
        }
    }

    /** 供前端展示用的格式白名单（下拉框直接取，避免前后端各写一份）。 */
    @GetMapping("/formats")
    public ApiResponse<List<String>> formats() {
        return ApiResponse.ok(java.util.Arrays.stream(FileFormat.values())
                .map(format -> format.name().toLowerCase(java.util.Locale.ROOT))
                .toList());
    }
}
