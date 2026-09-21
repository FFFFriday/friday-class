package com.fridayclass.dto;

import com.fridayclass.entity.ClassGroup;
import com.fridayclass.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 班级响应。对应班级列表与详情两种形态。
 *
 * <p>{@code members} 只在详情里填（列表为 {@code null}）——
 * 列表页显示 20 个班各自的全班名单，等于把几百行数据塞进一个列表响应，
 * 而界面上一个都不会显示。
 */
public record ClassGroupResponse(
        Long id,
        String name,
        String description,
        Long teacherId,
        String teacherName,
        long memberCount,
        long sessionCount,
        List<ClassGroupMemberResponse> members,
        LocalDateTime createdAt) {

    /** 列表用：不带成员。 */
    public static ClassGroupResponse summary(ClassGroup group, long memberCount, long sessionCount) {
        return build(group, memberCount, sessionCount, null);
    }

    /** 详情用：带成员。 */
    public static ClassGroupResponse detail(ClassGroup group,
                                           long sessionCount,
                                           List<ClassGroupMemberResponse> members) {
        return build(group, members == null ? 0 : members.size(), sessionCount, members);
    }

    private static ClassGroupResponse build(ClassGroup group,
                                            long memberCount,
                                            long sessionCount,
                                            List<ClassGroupMemberResponse> members) {
        User teacher = group.getTeacher();
        return new ClassGroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                teacher == null ? null : teacher.getId(),
                displayName(teacher),
                memberCount,
                sessionCount,
                members,
                group.getCreatedAt());
    }

    /** 优先昵称，没填回退用户名（与 SessionResponse / CoursewareResponse 口径一致）。 */
    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String nickname = user.getNickname();
        return (nickname == null || nickname.isBlank()) ? user.getUsername() : nickname;
    }
}
