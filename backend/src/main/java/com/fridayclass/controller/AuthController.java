package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.AuthResponse;
import com.fridayclass.dto.ChangePasswordRequest;
import com.fridayclass.dto.LoginRequest;
import com.fridayclass.dto.RegisterRequest;
import com.fridayclass.dto.RegisterResponse;
import com.fridayclass.dto.UpdateProfileRequest;
import com.fridayclass.dto.UserResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户中心接口。实现 docs/api-contract.md 第 1 节。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册（匿名可访问）。 */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /** 登录（匿名可访问），成功返回 JWT。 */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /** 当前登录用户。 */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(authService.me(principal.getId()));
    }

    /** 修改个人资料（目前只有昵称，用户名不可改）。返回改完后的用户信息。 */
    @PutMapping("/profile")
    public ApiResponse<UserResponse> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(principal.getId(), request));
    }

    /**
     * 修改密码。
     *
     * <p>仍然要求原密码 —— 前端那个「再次输入新密码」只是防打错，不能替代它。
     * 去掉原密码校验的话，任何拿到登录态的人（共用电脑没退、令牌被偷）都能直接改密码，
     * 把账号原主人锁在外面。
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.getId(), request);
        return ApiResponse.ok(null);
    }
}
