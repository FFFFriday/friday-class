package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.admin.AdminAuditLogResponse;
import com.fridayclass.entity.AdminAuditLog;
import com.fridayclass.entity.User;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.service.AdminAuditService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：审计日志（M6）。
 *
 * <p>只做「查」。写入统一走 {@code AdminAuditService.record}，
 * 按 Friday 指示**不配图表、不做统计**。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditService auditService;
    private final UserRepository userRepository;

    public AdminAuditController(AdminAuditService auditService, UserRepository userRepository) {
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    /** 日志列表。adminId / action 两个筛选都可选。 */
    @GetMapping
    public ApiResponse<PageResult<AdminAuditLogResponse>> list(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AdminAuditLog> logs = auditService.list(adminId, action, page, size);

        // 批量补操作人名字。
        // 日志表里只存了 adminId（**刻意不建外键关联**：审计是只增不改的流水，
        // 它的价值在于「当时发生了什么」这个事实本身，不该因为用户后来被删就查不出来）。
        // 所以名字在查询时补——一次批量查，不在循环里查库。
        Map<Long, String> names = new HashMap<>();
        List<Long> adminIds = logs.getContent().stream()
                .map(AdminAuditLog::getAdminId)
                .distinct()
                .toList();
        if (!adminIds.isEmpty()) {
            for (User user : userRepository.findAllById(adminIds)) {
                names.put(user.getId(), user.getUsername());
            }
        }

        return ApiResponse.ok(PageResult.from(logs, log ->
                AdminAuditLogResponse.from(log, names.get(log.getAdminId()))));
    }
}
