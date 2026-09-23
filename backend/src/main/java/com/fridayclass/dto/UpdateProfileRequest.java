package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料请求。对应 PUT /api/auth/profile。
 *
 * <p>目前**只有昵称**可改。用户名 {@code username} 是登录凭据 —— 它是 JWT 的主体、
 * 也是唯一索引的一列，改它等同于换账号，所以刻意不开放（要换用户名只能由管理员重建账号）。
 * 角色同理，不在这里。
 *
 * <p>昵称允许重复：它只是显示名，不是标识。全库真正需要唯一的只有 {@code username}。
 */
public record UpdateProfileRequest(
        @NotBlank(message = "名字不能为空")
        @Size(max = 50, message = "名字最长 50 个字")
        String nickname
) {
}
