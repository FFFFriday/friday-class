package com.fridayclass.dto;

import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.User;

import java.time.LocalDateTime;

/**
 * 课件响应。对应契约 2.1 / 2.3。
 *
 * <p>依赖调用方已通过 JOIN FETCH 取出 uploader：在 open-in-view=false 下，
 * 若在事务外访问懒加载关联会抛 LazyInitializationException。
 */
public record CoursewareResponse(
        Long id,
        String name,
        String status,
        Integer pageCount,
        Long uploaderId,
        String uploaderName,
        LocalDateTime uploadedAt) {

    public static CoursewareResponse from(Courseware courseware) {
        User uploader = courseware.getUploader();
        return new CoursewareResponse(
                courseware.getId(),
                courseware.getName(),
                courseware.getStatus().name(),
                courseware.getPageCount(),
                uploader == null ? null : uploader.getId(),
                displayName(uploader),
                courseware.getUploadedAt());
    }

    /** 优先展示昵称，没填则回退到用户名。 */
    private static String displayName(User uploader) {
        if (uploader == null) {
            return null;
        }
        String nickname = uploader.getNickname();
        return (nickname == null || nickname.isBlank()) ? uploader.getUsername() : nickname;
    }
}
