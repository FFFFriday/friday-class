package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。对应 POST /api/auth/login。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
