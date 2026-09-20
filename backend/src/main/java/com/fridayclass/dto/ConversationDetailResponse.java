package com.fridayclass.dto;

import java.util.List;

/**
 * 会话详情：会话头 + 全部消息。
 *
 * <p>消息一次性返回、不分页：单个会话有 200 条上限（见 M4 §3.5），
 * 一次给完比做游标分页简单得多，前端也不必维护「加载更早」的状态。
 */
public record ConversationDetailResponse(
        AiConversationResponse conversation,
        List<QaRecordResponse> messages) {

    public static ConversationDetailResponse of(AiConversationResponse conversation,
                                                List<QaRecordResponse> messages) {
        return new ConversationDetailResponse(conversation, messages);
    }
}
