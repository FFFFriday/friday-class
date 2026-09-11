package com.fridayclass.dto;

import com.fridayclass.entity.Courseware;

/**
 * 上传课件响应。对应契约 2.2。
 */
public record CoursewareUploadResponse(Long id, String name, String status, Integer pageCount) {

    public static CoursewareUploadResponse from(Courseware courseware) {
        return new CoursewareUploadResponse(
                courseware.getId(),
                courseware.getName(),
                courseware.getStatus().name(),
                courseware.getPageCount());
    }
}
