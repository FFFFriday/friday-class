package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.AuthResponse;
import com.fridayclass.dto.ChangePasswordRequest;
import com.fridayclass.dto.LoginRequest;
import com.fridayclass.dto.RegisterRequest;
import com.fridayclass.dto.RegisterResponse;
import com.fridayclass.dto.UserResponse;
import com.fridayclass.entity.User;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证业务：注册、登录、查询当前用户、修改密码。
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** 注册。用户名重复返回业务错误，密码以 BCrypt 哈希落库。 */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByUsernameAndDeletedFalse(request.username())) {
            throw new BusinessException("用户名已存在");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // 自助注册一律创建学生；教师账号由管理员预置（见 db/seed_teacher.sql），防止自助提权
        user.setRole(Role.STUDENT);
        user.setNickname(request.nickname());

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // 并发下唯一索引兜底：两个请求同时注册同一用户名
            throw new BusinessException("用户名已存在");
        }

        String token = jwtService.generateToken(saved);
        return new RegisterResponse(
                saved.getId(), saved.getUsername(), saved.getRole().name(), saved.getNickname(), token);
    }

    /**
     * 登录。用户名不存在与密码错误返回同一条提示，
     * 避免泄露「该用户名是否已注册」。
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // 软删除的账号不能登录，因此走 DeletedFalse 查询
        User user = userRepository.findByUsernameAndDeletedFalse(request.username())
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        return new AuthResponse(jwtService.generateToken(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return UserResponse.from(requireUser(userId));
    }

    /** 修改密码：校验原密码后再写入新哈希。 */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException("原密码错误");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // 自增令牌版本：改密码后此前签发的所有 JWT 立即失效，需重新登录
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }
}
