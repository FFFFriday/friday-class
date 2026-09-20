package com.fridayclass.controller.admin;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.admin.AdminCreateUserRequest;
import com.fridayclass.dto.admin.AdminUserResponse;
import com.fridayclass.dto.admin.AdminUserUpdateRequests;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AdminUserService;
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

/**
 * 管理端：账号管理（M6）。
 *
 * <h3>权限</h3>
 * 整棵 {@code /api/admin/**} 子树由 {@code SecurityConfig} 里的
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} 拦住。
 * <b>那一行是这个管理端的唯一安全边界</b>——漏了它不是「功能不好用」，
 * 而是任何登录学生都能删库、重置任何人密码。已实测：学生 403、教师 403。
 *
 * <p>本类不再写 {@code @PreAuthorize}：路径级已经拦死了，
 * 在每个方法上再写一遍只会多一处可能遗忘或写错的地方。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    /** 列表。支持 keyword（用户名/昵称）、role、disabled 三个筛选。 */
    @GetMapping
    public ApiResponse<PageResult<AdminUserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean disabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResult.from(
                userService.list(keyword, role, disabled, page, size),
                AdminUserResponse::from));
    }

    /** 新建账号。**可指定角色**——这是它与自助注册的关键区别。 */
    @PostMapping
    public ApiResponse<AdminUserResponse> create(
            @Valid @RequestBody AdminCreateUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(userService.create(principal.getId(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(AdminUserResponse.from(userService.requireUser(id)));
    }

    /** 禁用 / 启用。禁用会同时作废该账号已签发的令牌，不是「下次登录才生效」。 */
    @PutMapping("/{id}/status")
    public ApiResponse<AdminUserResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserUpdateRequests.StatusRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(
                userService.updateStatus(principal.getId(), id, request.disabled()));
    }

    /** 重置密码。同样作废旧令牌。 */
    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserUpdateRequests.PasswordRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        userService.resetPassword(principal.getId(), id, request.newPassword());
        return ApiResponse.ok(null);
    }

    /** 改角色。会挡住「把最后一个管理员降级」。 */
    @PutMapping("/{id}/role")
    public ApiResponse<AdminUserResponse> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserUpdateRequests.RoleRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(userService.changeRole(principal.getId(), id, request.role()));
    }

    /** 软删除。会挡住「删掉自己」与「删掉最后一个管理员」。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        userService.remove(principal.getId(), id);
        return ApiResponse.ok(null);
    }
}
