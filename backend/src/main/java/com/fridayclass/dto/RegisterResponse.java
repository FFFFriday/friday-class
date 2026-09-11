package com.fridayclass.dto;

/**
 * 注册响应。对应 POST /api/auth/register（扁平结构，token 在顶层，与前端取值一致）。
 */
public record RegisterResponse(Long id, String username, String role, String nickname, String token) {
}
