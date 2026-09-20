package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.AiConversationResponse;
import com.fridayclass.dto.ConversationDetailResponse;
import com.fridayclass.dto.CreateConversationRequest;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.RenameConversationRequest;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AiConversationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生 AI 会话接口（M4）。
 *
 * <h3>权限</h3>
 * 这组接口<b>不加角色限制</b>——教师也可以有自己的 AI 会话（备课答疑）。
 * 真正的边界是<b>归属校验</b>：每个方法都从 {@code @AuthenticationPrincipal} 取当前用户 ID，
 * 交给 Service 校验「这个会话是不是你的」。
 * 只靠 ID 就能读别人对话，才是这类接口最常见的漏洞。
 *
 * <p>提问入口<b>不在这里</b>，仍然是 {@code POST /api/qa/ask}
 * （加了一个可选的 {@code conversationId}）——刻意不新建第二个提问接口，
 * 免得两条路径的限流、幂等、计费逻辑各写一遍、慢慢分叉。
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class AiConversationController {

    private final AiConversationService conversationService;

    public AiConversationController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 我的会话列表（按最后活跃倒序）。 */
    @GetMapping
    public ApiResponse<ListResult<AiConversationResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(conversationService.list(principal.getId())));
    }

    /** 新建会话。三个字段全部可选。 */
    @PostMapping
    public ApiResponse<AiConversationResponse> create(
            @Valid @RequestBody(required = false) CreateConversationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(conversationService.create(principal.getId(), request));
    }

    /** 会话详情 + 全部消息。 */
    @GetMapping("/{id}")
    public ApiResponse<ConversationDetailResponse> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(conversationService.detail(principal.getId(), id));
    }

    /** 重命名。 */
    @PutMapping("/{id}")
    public ApiResponse<AiConversationResponse> rename(
            @PathVariable Long id,
            @Valid @RequestBody RenameConversationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(conversationService.rename(principal.getId(), id, request.title()));
    }

    /**
     * 删除会话（软删除）。
     *
     * <p>会话里的问答记录<b>保留</b>——它们是课堂记录的数据源，
     * 学生删掉自己的会话不该让老师那边的课堂记录出现缺口。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        conversationService.remove(principal.getId(), id);
        return ApiResponse.ok(null);
    }
}
