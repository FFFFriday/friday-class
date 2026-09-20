package com.fridayclass.dto;

import com.fridayclass.entity.ChatMessage;
import com.fridayclass.enums.ChatMessageStatus;

import java.time.LocalDateTime;

/**
 * 一条讨论区发言。
 *
 * <p>{@code content} 是<b>纯文本</b>。前端必须用
 * {@code {{ msg.content }}} 插值渲染，<b>严禁 {@code v-html}</b>——
 * 这是本项目的安全铁律，评审时重点检查。
 */
public record ChatMessageResponse(
        Long id,
        Long userId,
        String nickname,
        String role,
        String content,
        Long pageId,
        Integer pageNo,
        String status,
        LocalDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        boolean deleted = message.getStatus() == ChatMessageStatus.DELETED;

        // 已撤回的发言**不返回正文**。
        // 只置灰不给内容：老师撤回一条不当言论，如果正文还随历史接口发下去，
        // 撤回就只是视觉上的，刷新一下或者看一眼接口就全看见了。
        String content = deleted ? null : message.getContent();

        var user = message.getUser();
        var page = message.getPage();

        return new ChatMessageResponse(
                message.getId(),
                user == null ? null : user.getId(),
                user == null ? null : user.getNickname(),
                user == null || user.getRole() == null ? null : user.getRole().name(),
                content,
                page == null ? null : page.getId(),
                page == null ? null : page.getPageNo(),
                message.getStatus() == null ? null : message.getStatus().name(),
                message.getCreatedAt());
    }
}
