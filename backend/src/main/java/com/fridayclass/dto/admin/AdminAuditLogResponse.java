package com.fridayclass.dto.admin;

import com.fridayclass.entity.AdminAuditLog;

import java.time.LocalDateTime;

/**
 * 审计日志的一行。
 *
 * @param adminName 操作人用户名。日志表里只存了 {@code adminId}，
 *                  名字是查询时批量补上的——见控制器里的说明
 */
public record AdminAuditLogResponse(
        Long id,
        Long adminId,
        String adminName,
        String action,
        String targetType,
        Long targetId,
        String detail,
        LocalDateTime createdAt) {

    public static AdminAuditLogResponse from(AdminAuditLog log, String adminName) {
        return new AdminAuditLogResponse(
                log.getId(),
                log.getAdminId(),
                adminName,
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getDetail(),
                log.getCreatedAt());
    }
}
