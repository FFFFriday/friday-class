package com.fridayclass.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建课堂请求。对应 POST /api/session。
 */
public record SessionCreateRequest(

        @NotNull(message = "课件不能为空")
        Long coursewareId,

        /** 缺省时用课件名，见 {@code ClassSessionService.resolveTitle}。 */
        @Size(max = 200, message = "课堂名称最长 200 个字符")
        String title
) {
}
