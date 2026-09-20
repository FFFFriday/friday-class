package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ChatHistoryResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.ChatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课堂讨论区接口（M2）。
 *
 * <p><b>这里没有「发消息」接口</b>——发送走 WebSocket 的 {@code chat.send}。
 * 本控制器只管两件低频的事：<b>拉历史</b>和<b>删除</b>。
 *
 * <p>为什么不给发送也开一个 REST 口子：热点消息走 WS 才能做到「2 秒内全班可见」，
 * 而 REST 只负责那些「不能丢、频率低」的操作（见技术约定 §1.1）。
 * 两边都开会让前端要维护两套发送逻辑，还容易在限流上打架。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 拉发言历史。需登录（本类不在白名单，走 SecurityConfig 的默认「需认证」）。
     *
     * @param afterId  增量游标。传了且大于 0 → 只返回 id 比它大的（断线重连补拉）
     * @param beforeId 往前游标。传了且大于 0 → 返回比它更早的一页（「加载更早」）
     * @param limit    条数上限，缺省 50（增量路径缺省 200），封顶 500
     *
     * <p>两个游标都不传 = 首次进入，返回**最近** limit 条。
     * 三者优先级：beforeId &gt; afterId &gt; 都不传。同时传两个没有意义，按优先序取前者。
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<ChatHistoryResponse> messages(@PathVariable Long sessionId,
                                                     @RequestParam(required = false) Long afterId,
                                                     @RequestParam(required = false) Long beforeId,
                                                     @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(chatService.history(sessionId, afterId, beforeId, limit));
    }

    /**
     * 撤回一条发言（软删除）。
     *
     * <p>权限在 Service 里判：本课堂的授课教师，或任意管理员。
     * 这里不写 {@code @PreAuthorize("hasRole('TEACHER')")}——那会把管理员也挡在门外，
     * 而管理员按设计是可以撤回的。
     */
    @DeleteMapping("/messages/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        chatService.delete(id, principal.getId(), principal.getRole());
        return ApiResponse.ok(null);
    }
}
