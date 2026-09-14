package com.fridayclass.dto;

import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.User;

import java.time.LocalDateTime;

/**
 * 课堂响应。对应 POST /api/session、GET /api/session/{id}、POST /api/session/{id}/page。
 *
 * <p>三个接口返回**同一个结构**——创建完、查到、翻完页，前端要的字段是同一套，
 * 用不同结构只会让前端多写分支。
 *
 * <p><b>依赖调用方已 JOIN FETCH 出 courseware 与 teacher</b>：在
 * {@code open-in-view=false} 下，事务外访问懒加载关联会抛 {@code LazyInitializationException}。
 * 更麻烦的是 {@code Courseware} 上带 {@code @SQLRestriction("deleted = 0")}，
 * 若课件被软删除，懒加载代理会取不到行而抛 {@code ObjectRetrievalFailureException}。
 * 所以仓库层用 left join fetch，这里再对 null 做兜底。
 */
public record SessionResponse(
        Long id,
        Long coursewareId,
        String coursewareName,
        String title,
        String status,
        Long teacherId,
        String teacherName,
        Integer currentPage,
        String streamPushUrl,
        String streamPullUrl,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt) {

    public static SessionResponse from(ClassSession session) {
        Courseware courseware = session.getCourseware();
        User teacher = session.getTeacher();

        return new SessionResponse(
                session.getId(),
                courseware == null ? null : courseware.getId(),
                courseware == null ? null : courseware.getName(),
                session.getTitle(),
                session.getStatus() == null ? null : session.getStatus().name(),
                teacher == null ? null : teacher.getId(),
                displayName(teacher),
                session.getCurrentPage(),
                session.getStreamPushUrl(),
                session.getStreamPullUrl(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getCreatedAt());
    }

    /** 优先展示昵称，没填则回退到用户名（与 CoursewareResponse 口径一致）。 */
    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String nickname = user.getNickname();
        return (nickname == null || nickname.isBlank()) ? user.getUsername() : nickname;
    }
}
