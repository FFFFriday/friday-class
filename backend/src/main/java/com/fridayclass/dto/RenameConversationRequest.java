package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重命名会话。
 */
public record RenameConversationRequest(
        @NotBlank(message = "会话名称不能为空")
        @Size(max = 100, message = "会话名称最长 100 个字符")
        String title) {
}
