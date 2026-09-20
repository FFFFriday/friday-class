package com.fridayclass.dto;

import java.util.List;

/**
 * 讨论区历史。形状为 {@code { items: [...], hasMore: bool }}。
 *
 * <p>为什么不复用 {@link ListResult}：那个是 {@code { list: [...] }}，
 * 分页场景需要额外的游标信息，两个接口的响应形状会不一样。
 * 这里单列一个类型，前端只看 {@code items} 与 {@code hasMore} 两个字段。
 *
 * @param hasMore 是否还有更早的发言可拉（按 {@code limit} 取满时给出）
 */
public record ChatHistoryResponse(List<ChatMessageResponse> items, boolean hasMore) {

    public static ChatHistoryResponse of(List<ChatMessageResponse> items, boolean hasMore) {
        return new ChatHistoryResponse(items, hasMore);
    }
}
