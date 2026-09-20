package com.fridayclass.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员新建账号。
 *
 * <p>与自助注册（{@code RegisterRequest}）的关键区别：<b>这张表能指定角色</b>。
 * 自助注册写死建学生（防自助提权），而管理员建号本来就该能建教师——
 * 所以这个接口必须牢牢锁在 {@code /api/admin/**} 后面。
 */
public record AdminCreateUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 50, message = "用户名最长 50 个字符")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字与下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
        String password,

        /** TEACHER / STUDENT / ADMIN。见 {@code Role} 枚举。 */
        @NotBlank(message = "角色不能为空")
        String role,

        @Size(max = 50, message = "昵称最长 50 个字符")
        String nickname) {
}
