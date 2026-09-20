package com.fridayclass.dto.admin;

import com.fridayclass.entity.User;

import java.time.LocalDateTime;

/**
 * 管理端的用户视图。
 *
 * <p>比 {@code UserResponse} 多出 {@code disabled} 与 {@code createdAt}——
 * 那个是给用户自己看的（「我是谁」），这个是给管理员看的（「这个账号什么状态」）。
 * 刻意不复用：两者的字段集早晚会分叉，而混用一个 DTO 会让「谁该看到什么」
 * 变得说不清楚，密码哈希就是这类字段里最危险的一个。
 *
 * @param disabled 是否被禁用。<b>与「已删除」是两回事</b>——
 *                 被禁用的账号还在列表里，只是登不进来
 */
public record AdminUserResponse(
        Long id,
        String username,
        String nickname,
        String role,
        boolean disabled,
        LocalDateTime createdAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole() == null ? null : user.getRole().name(),
                Boolean.TRUE.equals(user.getDisabled()),
                user.getCreatedAt());
    }
}
