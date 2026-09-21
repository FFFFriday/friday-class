package com.fridayclass.dto;

import com.fridayclass.entity.ClassGroupMember;

import java.time.LocalDateTime;

/**
 * 班级成员。对应 GET /api/class-groups/{id} 的 members 数组。
 */
public record ClassGroupMemberResponse(
        Long userId,
        String username,
        String nickname,
        LocalDateTime joinedAt) {

    public static ClassGroupMemberResponse from(ClassGroupMember member) {
        var user = member.getUser();
        return new ClassGroupMemberResponse(
                user == null ? null : user.getId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getNickname(),
                member.getJoinedAt());
    }
}
