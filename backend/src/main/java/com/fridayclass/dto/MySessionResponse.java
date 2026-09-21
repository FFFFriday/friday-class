package com.fridayclass.dto;

import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学生端「我的课堂」条目。对应 GET /api/session/my-sessions。
 *
 * <p>比 {@link SessionResponse} 多两样东西：
 * <ul>
 *   <li>{@code classGroupNames} —— 这节课是给哪几个班上的（**合班时是多个**）。
 *       学生要能一眼看出「这节是不是给我班上的」；</li>
 *   <li>{@code hasSummary} —— 有没有生成过总结。首页的「回顾」按钮靠它决定
 *       是直接进回顾页，还是提示「尚无总结」。</li>
 * </ul>
 *
 * <p>{@code visibility} 也返回：学生偶尔会问「为什么这节我看得到」，
 * 公开课与点名课的答案不一样。
 */
public record MySessionResponse(
        Long id,
        String title,
        String status,
        String visibility,
        Long coursewareId,
        String coursewareName,
        Long teacherId,
        String teacherName,
        List<String> classGroupNames,
        Integer currentPage,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt,
        boolean hasSummary) {

    public static MySessionResponse from(ClassSession session,
                                         List<String> classGroupNames,
                                         boolean hasSummary) {
        Courseware courseware = session.getCourseware();
        User teacher = session.getTeacher();
        return new MySessionResponse(
                session.getId(),
                session.getTitle(),
                session.getStatus() == null ? null : session.getStatus().name(),
                session.getVisibility() == null ? null : session.getVisibility().name(),
                courseware == null ? null : courseware.getId(),
                courseware == null ? null : courseware.getName(),
                teacher == null ? null : teacher.getId(),
                displayName(teacher),
                classGroupNames == null ? List.of() : classGroupNames,
                session.getCurrentPage(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getCreatedAt(),
                hasSummary);
    }

    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String nickname = user.getNickname();
        return (nickname == null || nickname.isBlank()) ? user.getUsername() : nickname;
    }
}
