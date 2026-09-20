package com.fridayclass.dto;

import com.fridayclass.entity.AiConversation;

import java.time.LocalDateTime;

/**
 * AI 会话（列表项 / 详情头）。
 *
 * <p>{@code coursewareId} / {@code sessionId} 是「创建时的来源标记」，
 * 用于筛选与归档，<b>不是上下文边界</b>——同一份课件下开的两个会话照样互不可见。
 *
 * @param messageCount 会话里的问答条数。一条问答记录 = 一轮对话
 */
public record AiConversationResponse(
        Long id,
        String title,
        Long coursewareId,
        String coursewareName,
        Long sessionId,
        Long messageCount,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt) {

    public static AiConversationResponse from(AiConversation conversation, Long messageCount) {
        var courseware = conversation.getCourseware();
        return new AiConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                courseware == null ? null : courseware.getId(),
                courseware == null ? null : courseware.getName(),
                conversation.getSession() == null ? null : conversation.getSession().getId(),
                messageCount,
                conversation.getLastActiveAt(),
                conversation.getCreatedAt());
    }
}
