package com.fridayclass.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话。三个字段<b>全部可选</b>。
 *
 * <p>全部可空是有意的：学生可以在课堂里点「问 AI」（带上 coursewareId + sessionId），
 * 也可以在「AI 助手」页直接新建一个空白会话（什么都不带，纯自由问答）。
 */
public record CreateConversationRequest(
        Long coursewareId,

        Long sessionId,

        @Size(max = 100, message = "会话名称最长 100 个字符")
        String title) {
}
