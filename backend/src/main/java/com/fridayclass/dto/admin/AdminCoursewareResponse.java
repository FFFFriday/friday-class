package com.fridayclass.dto.admin;

import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.User;

import java.time.LocalDateTime;

/**
 * 管理端的课件视图。
 *
 * <p>比 {@code CoursewareResponse} 多出两个磁盘占用字段——
 * 管理端要能回答「存储都被谁占了」，而普通课件列表不关心这个。
 *
 * @param fileSizeBytes   源 .pptx 大小
 * @param slidesSizeBytes 该课件幻灯片目录的总大小。
 *                        和 fileSizeBytes 一起看才说明问题：一份 5MB 的课件
 *                        可能生成几十 MB 的幻灯片图片
 */
public record AdminCoursewareResponse(
        Long id,
        String name,
        String status,
        Integer pageCount,
        Long uploaderId,
        String uploaderName,
        LocalDateTime uploadedAt,
        long fileSizeBytes,
        long slidesSizeBytes) {

    public static AdminCoursewareResponse from(Courseware courseware,
                                               long fileSizeBytes,
                                               long slidesSizeBytes) {
        User uploader = courseware.getUploader();
        return new AdminCoursewareResponse(
                courseware.getId(),
                courseware.getName(),
                courseware.getStatus() == null ? null : courseware.getStatus().name(),
                courseware.getPageCount(),
                uploader == null ? null : uploader.getId(),
                uploader == null ? null : uploader.getUsername(),
                courseware.getUploadedAt(),
                fileSizeBytes,
                slidesSizeBytes);
    }
}
