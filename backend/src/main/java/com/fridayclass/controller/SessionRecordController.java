package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ChatHistoryResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.SessionRecordOverview;
import com.fridayclass.dto.SessionSummaryResponse;
import com.fridayclass.dto.StudentQaGroup;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.SessionRecordService;
import com.fridayclass.service.SessionSummaryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课堂记录与总结接口（M5）。
 *
 * <h3>权限：本课堂的授课教师，或任意管理员</h3>
 * 这组接口<b>刻意不写 {@code @PreAuthorize("hasRole('TEACHER')")}</b>——
 * 那会把管理员挡在门外，而管理员按设计是可以看全部课堂的。
 * 真正的判定在 Service 的 {@code requireAccess} 里：<b>是谁</b>比<b>什么角色</b>更重要。
 *
 * <p>学生看不到课堂记录：里面包含全班同学的发言与提问，不是个人数据。
 * （学生自己的对话在「AI 助手」页，那里走的是会话归属校验。）
 */
@RestController
@RequestMapping("/api/session/{sessionId}")
public class SessionRecordController {

    private final SessionRecordService recordService;
    private final SessionSummaryService summaryService;

    public SessionRecordController(SessionRecordService recordService,
                                   SessionSummaryService summaryService) {
        this.recordService = recordService;
        this.summaryService = summaryService;
    }

    /** 概览：时长、参与人数、发言数、问答数。 */
    @GetMapping("/record/overview")
    public ApiResponse<SessionRecordOverview> overview(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(
                recordService.overview(sessionId, principal.getId(), principal.getRole()));
    }

    /**
     * 发言时间线。分页语义与讨论区一致（游标而非 offset）。
     *
     * @param beforeId 往前翻：返回比它更早的一页
     * @param afterId  增量：返回比它更新的
     */
    @GetMapping("/record/chat")
    public ApiResponse<ChatHistoryResponse> chat(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(recordService.chatTimeline(
                sessionId, principal.getId(), principal.getRole(), beforeId, afterId, limit));
    }

    /**
     * 按学生分组的 AI 问答。
     *
     * <p><b>没跟 AI 聊过的学生不会出现在结果里</b>——这是明确需求。
     * 实现上是从问答记录出发分组，没有记录自然构不出那一组。
     */
    @GetMapping("/record/qa")
    public ApiResponse<ListResult<StudentQaGroup>> studentQa(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(
                recordService.studentQa(sessionId, principal.getId(), principal.getRole())));
    }

    /**
     * 触发生成课堂总结（异步）。
     *
     * <p>立即返回 PENDING，真正的模型调用在后台跑，前端轮询 {@code GET /summary}。
     * 无记录时返回 {@code BIZ_SUMMARY_EMPTY} 且**不调模型**；
     * 正在生成时返回 {@code BIZ_SUMMARY_RUNNING} 且**不重复调模型**。
     */
    @PostMapping("/summary")
    public ApiResponse<SessionSummaryResponse> generateSummary(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(
                summaryService.generate(sessionId, principal.getId(), principal.getRole()));
    }

    /** 读取总结（含状态）。没生成过时 {@code status} 为 null。 */
    @GetMapping("/summary")
    public ApiResponse<SessionSummaryResponse> getSummary(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(
                summaryService.get(sessionId, principal.getId(), principal.getRole()));
    }
}
