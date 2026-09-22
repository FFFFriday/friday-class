package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ClassGroupMembersRequest;
import com.fridayclass.dto.ClassGroupResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.dto.StudentCandidateResponse;
import com.fridayclass.dto.admin.AdminClassGroupRequest;
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
import java.util.Objects;

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

    /**
     * 建班。{@code teacherId} 可空 —— 不传则班主任落成操作的管理员自己（沿用改造前的行为）。
     *
     * <p>管理端建班必须能把班直接交给某位老师：否则建出来的班归属管理员，
     * 而管理员没有教师端入口，那个班就成了没人能上课的孤儿班。
     */
    @PostMapping
    public ApiResponse<ClassGroupResponse> create(@Valid @RequestBody AdminClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        ClassGroupResponse created = classGroupService.createForAdmin(
                principal.getId(), request.teacherId(), request.toBase());
        auditService.record(principal.getId(), AdminAuditService.CLASS_CREATE,
                AdminAuditService.TARGET_CLASS_GROUP, created.id(),
                "新建班级：" + created.name()
                        + (request.teacherId() == null
                        ? "" : "（班主任 " + created.teacherName() + "）"));
        return ApiResponse.ok(created);
    }

    /**
     * 改班：改名 + <b>可选</b>换班主任。{@code teacherId} 为 null 表示不动归属。
     *
     * <p>换归属会额外记一条 {@link AdminAuditService#CLASS_REASSIGN}。
     * 这里用**教师显示名**比对前后，而不是 id —— 前端下拉框没被动过时两边相等，
     * 就不会白记一条「更换班主任」污染审计日志。
     */
    @PutMapping("/{id}")
    public ApiResponse<ClassGroupResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody AdminClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        String previousTeacher = classGroupService.detail(id, principal.getId(), true).teacherName();

        ClassGroupResponse updated = classGroupService.updateForAdmin(
                id, request.toBase(), request.teacherId(), principal.getId());

        auditService.record(principal.getId(), AdminAuditService.CLASS_UPDATE,
                AdminAuditService.TARGET_CLASS_GROUP, id, "修改班级：" + updated.name());

        if (request.teacherId() != null
                && !Objects.equals(previousTeacher, updated.teacherName())) {
            auditService.record(principal.getId(), AdminAuditService.CLASS_REASSIGN,
                    AdminAuditService.TARGET_CLASS_GROUP, id,
                    "更换班主任：" + previousTeacher + " → " + updated.teacherName());
        }
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

    @GetMapping("/{id}/sessions")
    public ApiResponse<ListResult<SessionResponse>> sessions(@PathVariable Long id,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(classGroupService.sessionsOf(id, principal.getId(), true)));
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
