package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ClassGroupMembersRequest;
import com.fridayclass.dto.ClassGroupRequest;
import com.fridayclass.dto.ClassGroupResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.StudentCandidateResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AdminAuditService;
import com.fridayclass.service.ClassGroupService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端班级管理（问题点 8）。
 *
 * <p>放在 {@code /api/admin/**} 下，**自动被现有的
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} 保护**，
 * 不需要在此新增任何安全配置。
 *
 * <p>与教师端共用 {@link ClassGroupService}，唯一差别是传 {@code isAdmin = true}
 * ——「不受归属限制、可跨教师操作」。
 *
 * <p>每个写操作都记审计（见 {@link AdminAuditService} 的 {@code CLASS_*} 动作码）。
 * 教师操作自己的班不记，只有跨教师的管理动作才留痕，避免审计日志被日常流水淹没。
 */
@RestController
@RequestMapping("/api/admin/class-groups")
public class AdminClassGroupController {

    private final ClassGroupService classGroupService;
    private final AdminAuditService auditService;

    public AdminClassGroupController(ClassGroupService classGroupService,
                                     AdminAuditService auditService) {
        this.classGroupService = classGroupService;
        this.auditService = auditService;
    }

    /** 全部班级，可按教师筛选。 */
    @GetMapping
    public ApiResponse<PageResult<ClassGroupResponse>> list(
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(classGroupService.list(null, true, teacherId, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ClassGroupResponse> detail(@PathVariable Long id,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classGroupService.detail(id, principal.getId(), true));
    }

    @PostMapping
    public ApiResponse<ClassGroupResponse> create(@Valid @RequestBody ClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        // 管理端建班时「班主任」暂时落成管理员自己；后续可由教师版接口改归属。
        // 之所以不额外开一个「指定 teacherId」的字段：那需要一套「把班转给谁」的
        // 完整语义（原班主任怎么办、成员留不留），本次需求里没有，硬做只会做出半成品。
        ClassGroupResponse created = classGroupService.create(principal.getId(), request);
        auditService.record(principal.getId(), AdminAuditService.CLASS_CREATE,
                AdminAuditService.TARGET_CLASS_GROUP, created.id(), "新建班级：" + created.name());
        return ApiResponse.ok(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<ClassGroupResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        ClassGroupResponse updated = classGroupService.update(id, request, principal.getId(), true);
        auditService.record(principal.getId(), AdminAuditService.CLASS_UPDATE,
                AdminAuditService.TARGET_CLASS_GROUP, id, "修改班级：" + updated.name());
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        // 先把名字取出来再删 —— 删完就查不到了，审计里只剩一个光秃秃的 id
        String name = classGroupService.detail(id, principal.getId(), true).name();
        classGroupService.delete(id, principal.getId(), true);
        auditService.record(principal.getId(), AdminAuditService.CLASS_DELETE,
                AdminAuditService.TARGET_CLASS_GROUP, id, "删除班级：" + name);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/candidates")
    public ApiResponse<ListResult<StudentCandidateResponse>> candidates(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(
                classGroupService.candidates(id, principal.getId(), true, keyword, page, size));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<Map<String, Integer>> addMembers(
            @PathVariable Long id,
            @Valid @RequestBody ClassGroupMembersRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        int added = classGroupService.addMembers(id, request, principal.getId(), true);
        auditService.record(principal.getId(), AdminAuditService.CLASS_MEMBER_ADD,
                AdminAuditService.TARGET_CLASS_GROUP, id,
                "加入班级：" + added + " 人（请求 " + request.userIds().size() + " 人）");
        return ApiResponse.ok(Map.of("added", added));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id,
                                          @PathVariable Long userId,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        classGroupService.removeMember(id, userId, principal.getId(), true);
        auditService.record(principal.getId(), AdminAuditService.CLASS_MEMBER_REMOVE,
                AdminAuditService.TARGET_CLASS_GROUP, id, "移出班级：userId=" + userId);
        return ApiResponse.ok(null);
    }
}
