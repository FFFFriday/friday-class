package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.admin.AdminCoursewareResponse;
import com.fridayclass.dto.admin.StorageCleanRequest;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AdminAuditService;
import com.fridayclass.service.AdminCoursewareService;
import com.fridayclass.service.StorageScanService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：课件与存储（M6）。
 *
 * <p>类映射写 {@code /api/admin} 而不是 {@code /api/admin/coursewares}：
 * 本控制器同时管 {@code /coursewares/**} 与 {@code /storage/**} 两组路径，
 * 挂在 {@code /api/admin} 下两条都能自然声明。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCoursewareController {

    private final AdminCoursewareService coursewareService;
    private final StorageScanService storageScanService;
    private final AdminAuditService auditService;

    public AdminCoursewareController(AdminCoursewareService coursewareService,
                                     StorageScanService storageScanService,
                                     AdminAuditService auditService) {
        this.coursewareService = coursewareService;
        this.storageScanService = storageScanService;
        this.auditService = auditService;
    }

    // ── 课件 ──────────────────────────────────────────────────

    /** 课件列表（含源文件与幻灯片占用）。 */
    @GetMapping("/coursewares")
    public ApiResponse<PageResult<AdminCoursewareResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResult.from(
                coursewareService.list(page, size),
                coursewareService::toResponse));
    }

    /** 删除课件：DB 行 + 源文件 + 幻灯片目录，三处一起清。 */
    @DeleteMapping("/coursewares/{id}")
    public ApiResponse<AdminCoursewareService.DeleteReport> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        AdminCoursewareService.DeleteReport report = coursewareService.delete(id);

        auditService.record(principal.getId(), AdminAuditService.COURSEWARE_DELETE,
                AdminAuditService.TARGET_COURSEWARE, id,
                "删除课件《" + report.name() + "》：源文件" + (report.sourceRemoved() ? "已删" : "未删")
                        + "，幻灯片清理 " + report.slidesRemoved() + " 个文件");

        return ApiResponse.ok(report);
    }

    /**
     * 重新解析。<b>会真调付费模型</b>（百页课件约 0.8 元），前端必须二次确认。
     */
    @PostMapping("/coursewares/{id}/reparse")
    public ApiResponse<Void> reparse(@PathVariable Long id,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        coursewareService.reparse(id);
        auditService.record(principal.getId(), AdminAuditService.COURSEWARE_REPARSE,
                AdminAuditService.TARGET_COURSEWARE, id, "触发重新解析（会真调付费模型）");
        return ApiResponse.ok(null);
    }

    // ── 存储 ──────────────────────────────────────────────────

    /** 占用统计。 */
    @GetMapping("/storage/overview")
    public ApiResponse<StorageScanService.StorageOverview> storageOverview() {
        return ApiResponse.ok(storageScanService.overview());
    }

    /**
     * 孤立文件列表。<b>只列不删。</b>
     *
     * <p>要清理必须把这个列表里的 path 传回 {@code /orphans/clean}，
     * 服务端还会重新校验一次。不做「自动清理」——那不可逆，
     * 判断一旦有偏差就是把整个存储目录扫空。
     */
    @GetMapping("/storage/orphans")
    public ApiResponse<List<StorageScanService.Orphan>> storageOrphans() {
        return ApiResponse.ok(storageScanService.scanOrphans());
    }

    /** 清理指定的孤立文件。 */
    @PostMapping("/storage/orphans/clean")
    public ApiResponse<StorageScanService.CleanReport> cleanOrphans(
            @Valid @RequestBody StorageCleanRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        StorageScanService.CleanReport report = storageScanService.clean(request.paths());

        auditService.record(principal.getId(), AdminAuditService.STORAGE_ORPHAN_CLEAN,
                AdminAuditService.TARGET_STORAGE, null,
                "清理孤立文件：请求 " + report.requested() + " 个，实删 " + report.removed()
                        + " 个，跳过 " + report.skipped().size() + " 个");

        return ApiResponse.ok(report);
    }
}
