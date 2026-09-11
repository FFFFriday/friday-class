package com.fridayclass.dto;

/**
 * 登录响应。对应 POST /api/auth/login。
 */
public record AuthResponse(String token, UserResponse user) {
}
