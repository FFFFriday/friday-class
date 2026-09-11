package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。对应 POST /api/auth/register。
 *
 * <p>安全说明：注册**不接收** role 字段。自助注册一律创建学生账号；
 * 教师账号由管理员通过 {@code db/seed_teacher.sql} 预置（或注册后由管理员改角色），
 * 否则任何人选一下「教师」就能自助提权。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度需在 3~50 之间")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
        String password,

        @Size(max = 50, message = "昵称最长 50 个字符")
        String nickname
) {
}
