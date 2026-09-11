package com.fridayclass.dto;

import com.fridayclass.entity.User;

/**
 * 用户信息响应。role 用字符串输出（TEACHER / STUDENT），与前端判断一致。
 */
public record UserResponse(Long id, String username, String role, String nickname) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                user.getNickname());
    }
}
