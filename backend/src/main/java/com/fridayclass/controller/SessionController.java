package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageChangeRequest;
import com.fridayclass.dto.SessionCreateRequest;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.ClassSessionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课堂与翻页接口。实现 F003 的 HTTP 部分。
 *
 * <p>翻页为什么走 HTTP 而不是 WebSocket：WebSocket 一断老师就翻不了页，
 * 而 HTTP 只要网络通就行，还能直接用 curl 调试。WebSocket 只负责**把页码推给学生**
 * （见 {@code ws/PageWebSocketHandler}）。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final ClassSessionService classSessionService;

    public SessionController(ClassSessionService classSessionService) {
        this.classSessionService = classSessionService;
    }

    /**
     * 开课（教师专属）。
     *
     * <p><b>幂等</b>：若该教师对这份课件已有未结束的课堂，直接返回那一节，不再新建。
     * 这样「开始上课」按钮重复点、刷新页面重新进都不会制造重复课堂。
     */
    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<SessionResponse> create(@Valid @RequestBody SessionCreateRequest request,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classSessionService.create(principal.getId(), request));
    }

    /**
     * 我（当前教师）名下所有未结束的课堂。
     *
     * <p>必须声明在 {@link #detail} 之前，理由同 {@link #active()}。
     *
     * <p>与 {@link #active()} 的分工：{@code /active} 答的是「现在有什么课在讲」（给所有人看），
     * 这个答的是「我的课在哪」（含还没开始翻页的），老师退出控制台后靠它回来。
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<ListResult<SessionResponse>> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(classSessionService.listMine(principal.getId())));
    }

    /**
     * 下课（教师专属，且只能结束自己的课堂）。幂等，重复调用不报错。
     */
    @PostMapping("/{id}/end")
    @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<SessionResponse> end(@PathVariable Long id,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classSessionService.end(id, principal.getId()));
    }

    /**
     * 正在直播的课堂列表（门户「正在直播」入口）。
     *
     * <p>⚠️ <b>必须声明在 {@link #detail} 之前</b>。否则 {@code /api/session/active}
     * 会去匹配 {@code /{id}}，把 "active" 当成 Long 解析失败，返回
     * {@code 参数格式不正确：id} 而不是课堂列表。
     * （Spring 的 PathPattern 其实会优先匹配字面量段，但显式排在前面更不容易被后人改坏。）
     */
    @GetMapping("/active")
    public ApiResponse<ListResult<SessionResponse>> active() {
        return ApiResponse.ok(ListResult.of(classSessionService.listActive()));
    }

    /** 课堂详情（学生加入用）。 */
    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(classSessionService.detail(id));
    }

    /**
     * 翻页（教师专属，且只能翻自己的课堂）。
     *
     * <p>返回翻页后的课堂详情，前端不必再补一次 GET。
     */
    @PostMapping("/{id}/page")
    @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<SessionResponse> changePage(@PathVariable Long id,
                                                   @Valid @RequestBody PageChangeRequest request,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(
                classSessionService.updateCurrentPage(id, principal.getId(), request.pageNo()));
    }
}
