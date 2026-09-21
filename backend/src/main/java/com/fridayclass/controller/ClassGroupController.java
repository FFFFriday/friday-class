package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ClassGroupMembersRequest;
import com.fridayclass.dto.ClassGroupRequest;
import com.fridayclass.dto.ClassGroupResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.StudentCandidateResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.ClassGroupService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 教师端班级管理（问题点 6）。
 *
 * <p>⚠ <b>必须在 {@code SecurityConfig} 里显式加白名单</b>：
 * 项目是 deny-by-default，不加规则的话这里每个接口都会 403 ——
 * 而且症状是「功能不好用」，很容易被误当成前端 bug 查半天。
 *
 * <p>角色限制用类级 {@code @PreAuthorize} 一次声明，方法上不再重复写。
 * 真正的墙仍在 {@code SecurityConfig}（方法级注解是 AOP，属于纵深防御）。
 */
@RestController
@RequestMapping("/api/class-groups")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ClassGroupController {

    private final ClassGroupService classGroupService;

    public ClassGroupController(ClassGroupService classGroupService) {
        this.classGroupService = classGroupService;
    }

    /** 我的班级列表。教师端永远是「自己的」，teacherId 由登录态决定，不接受前端传参。 */
    @GetMapping
    public ApiResponse<PageResult<ClassGroupResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(classGroupService.list(principal.getId(), false, null, keyword, page, size));
    }

    /** 我的全部班级（不翻页，供开课弹窗多选）。 */
    @GetMapping("/mine")
    public ApiResponse<ListResult<ClassGroupResponse>> mine(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(ListResult.of(classGroupService.listMine(principal.getId())));
    }

    /** 建班。{@code teacherId} 取登录用户，请求体里没有这个字段。 */
    @PostMapping
    public ApiResponse<ClassGroupResponse> create(@Valid @RequestBody ClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classGroupService.create(principal.getId(), request));
    }

    /** 班级详情 + 成员名单。 */
    @GetMapping("/{id}")
    public ApiResponse<ClassGroupResponse> detail(@PathVariable Long id,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classGroupService.detail(id, principal.getId(), false));
    }

    /** 改名 / 改备注。 */
    @PutMapping("/{id}")
    public ApiResponse<ClassGroupResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ClassGroupRequest request,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(classGroupService.update(id, request, principal.getId(), false));
    }

    /** 删除班级（软删除，不影响已开课堂的回顾）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        classGroupService.delete(id, principal.getId(), false);
        return ApiResponse.ok(null);
    }

    /** 可加入的学生（已排除班内已有的人）。 */
    @GetMapping("/{id}/candidates")
    public ApiResponse<ListResult<StudentCandidateResponse>> candidates(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(
                classGroupService.candidates(id, principal.getId(), false, keyword, page, size));
    }

    /** 批量加人。返回实际新增人数（已去重、已跳过班内已有的人）。 */
    @PostMapping("/{id}/members")
    public ApiResponse<Map<String, Integer>> addMembers(
            @PathVariable Long id,
            @Valid @RequestBody ClassGroupMembersRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        int added = classGroupService.addMembers(id, request, principal.getId(), false);
        return ApiResponse.ok(Map.of("added", added));
    }

    /** 移出班级。 */
    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id,
                                          @PathVariable Long userId,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        classGroupService.removeMember(id, userId, principal.getId(), false);
        return ApiResponse.ok(null);
    }
}
