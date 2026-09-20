package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.dto.admin.BatchResult;
import com.fridayclass.dto.admin.KickRequest;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AdminSessionService;
import com.fridayclass.service.ClassPresenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：课堂管理（M6）。
 *
 * <p><b>批量操作的三个接口必须声明在 {@code /{id}} 之前</b>——
 * 否则 {@code /sessions/pause-all} 会去匹配 {@code /{id}}，
 * 把 "pause-all" 当 Long 解析失败，返回「参数格式不正确：id」而不是执行批量。
 * （Spring 的 PathPattern 其实会优先匹配字面量段，但显式排在前面更不容易被后人改坏。）
 */
@RestController
@RequestMapping("/api/admin/sessions")
public class AdminSessionController {

    private final AdminSessionService sessionService;

    public AdminSessionController(AdminSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 全局课堂列表。status / teacherId / keyword 三个筛选都可选。 */
    @GetMapping
    public ApiResponse<PageResult<SessionResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResult.from(
                sessionService.list(status, teacherId, keyword, page, size),
                SessionResponse::from));
    }

    // ── 批量（必须排在 /{id} 之前）────────────────────────────

    /**
     * 暂停所有**进行中**的课堂。
     *
     * <p>⚠️ 服务端切不断画面——媒体流是点对点直连的。
     * 这里的实际效果是：状态置 PAUSED + 广播 + 学生端盖遮罩 + 禁用讨论与提问，
     * <b>画面要靠老师端收到通知后自行停止共享</b>。
     */
    @PostMapping("/pause-all")
    public ApiResponse<BatchResult> pauseAll(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(sessionService.pauseAll(principal.getId()));
    }

    /** 恢复所有已暂停的课堂。 */
    @PostMapping("/resume-all")
    public ApiResponse<BatchResult> resumeAll(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(sessionService.resumeAll(principal.getId()));
    }

    /** 强制结束所有进行中的课堂。 */
    @PostMapping("/end-all")
    public ApiResponse<BatchResult> endAll(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(sessionService.endAll(principal.getId()));
    }

    // ── 单个 ──────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(SessionResponse.from(sessionService.requireSession(id)));
    }

    /** 实时在线名单。数据源是内存登记表，不是数据库（见 ClassPresenceService）。 */
    @GetMapping("/{id}/online")
    public ApiResponse<ListResult<ClassPresenceService.OnlineUser>> online(@PathVariable Long id) {
        return ApiResponse.ok(ListResult.of(sessionService.online(id)));
    }

    /** 强制下课。会记下 ended_by / end_reason，让「这课是谁结的」可追溯。 */
    @PostMapping("/{id}/end")
    public ApiResponse<Void> forceEnd(@PathVariable Long id,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        sessionService.forceEnd(principal.getId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<Void> pause(@PathVariable Long id,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        sessionService.pause(principal.getId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<Void> resume(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        sessionService.resume(principal.getId(), id);
        return ApiResponse.ok(null);
    }

    /** 踢人。先发「你被踢了」再关连接，否则学生只会看到连接莫名断开。 */
    @PostMapping("/{id}/kick")
    public ApiResponse<Void> kick(@PathVariable Long id,
                                  @Valid @RequestBody KickRequest request,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        sessionService.kick(principal.getId(), id, request.userId(), request.reason());
        return ApiResponse.ok(null);
    }
}
