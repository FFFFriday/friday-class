package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.admin.AdminCreateUserRequest;
import com.fridayclass.dto.admin.AdminUserResponse;
import com.fridayclass.entity.User;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端的账号管理（M6）。
 *
 * <h3>三道「别把自己锁在门外」的护栏</h3>
 * 账号管理最容易出的事故不是功能不对，是<b>管理员把自己或全部管理员干掉了</b>，
 * 之后再也没人能进管理端。所以这里显式挡住三件事：
 * <ol>
 *   <li>不能禁用自己；</li>
 *   <li>不能删除自己；</li>
 *   <li>不能把<b>最后一个</b>管理员降级或删除。</li>
 * </ol>
 * 这三条都不是「技术上做不到」，而是「做到了就只能改数据库才救得回来」。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            AdminAuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /** 列表。三个筛选条件都可选。 */
    @Transactional(readOnly = true)
    public Page<User> list(String keyword, String role, Boolean disabled, int page, int size) {
        String kw = blankToNull(keyword);
        Role parsedRole = parseRoleOrNull(role);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return userRepository.searchForAdmin(
                kw != null, kw == null ? "" : kw,
                parsedRole != null, parsedRole,
                disabled != null, disabled == null ? Boolean.FALSE : disabled,
                PageRequest.of(Math.max(page, 1) - 1, safeSize));
    }

    /** 详情。 */
    @Transactional(readOnly = true)
    public User requireUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
    }

    /** 新建账号（可指定角色）。 */
    @Transactional
    public AdminUserResponse create(Long adminId, AdminCreateUserRequest request) {
        String username = request.username().strip();
        if (userRepository.existsByUsernameAndDeletedFalse(username)) {
            throw new BusinessException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(parseRole(request.role()));
        user.setNickname(blankToNull(request.nickname()));

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // 并发下唯一索引兜底
            throw new BusinessException("用户名已存在");
        }

        auditService.record(adminId, AdminAuditService.USER_CREATE,
                AdminAuditService.TARGET_USER, saved.getId(),
                "新建账号 " + username + "，角色 " + saved.getRole().name());

        log.info("admin_user_created adminId={} userId={} role={}",
                adminId, saved.getId(), saved.getRole());
        return AdminUserResponse.from(saved);
    }

    /**
     * 禁用 / 启用。
     *
     * <p>禁用会**同时自增令牌版本**，让这个账号已签发的 JWT 立即失效——
     * 否则「禁用」只是拦住了下一次登录，此刻已经在线的人照样能用到令牌过期（7 天）。
     * 这一点很重要：管理员点「禁用」的预期是「他现在就用不了了」。
     */
    @Transactional
    public AdminUserResponse updateStatus(Long adminId, Long userId, boolean disabled) {
        if (disabled && adminId.equals(userId)) {
            throw new BusinessException("不能禁用自己的账号——那样你就再也进不来管理端了");
        }

        User user = requireUser(userId);
        user.setDisabled(disabled);
        if (disabled) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        User saved = userRepository.save(user);

        auditService.record(adminId,
                disabled ? AdminAuditService.USER_DISABLE : AdminAuditService.USER_ENABLE,
                AdminAuditService.TARGET_USER, userId,
                (disabled ? "禁用" : "启用") + "账号 " + saved.getUsername());

        log.info("admin_user_status adminId={} userId={} disabled={}", adminId, userId, disabled);
        return AdminUserResponse.from(saved);
    }

    /** 重置密码。同样自增令牌版本，把旧令牌全部作废。 */
    @Transactional
    public void resetPassword(Long adminId, Long userId, String newPassword) {
        User user = requireUser(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        auditService.record(adminId, AdminAuditService.USER_RESET_PWD,
                AdminAuditService.TARGET_USER, userId, "重置账号 " + user.getUsername() + " 的密码");
        log.info("admin_user_reset_pwd adminId={} userId={}", adminId, userId);
    }

    /** 改角色。 */
    @Transactional
    public AdminUserResponse changeRole(Long adminId, Long userId, String roleName) {
        Role role = parseRole(roleName);
        User user = requireUser(userId);

        // 把最后一个管理员降级 = 从此没人能进管理端
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && isLastAdmin()) {
            throw new BusinessException("这是最后一个管理员，不能改角色——改完就没人能进管理端了");
        }

        Role before = user.getRole();
        user.setRole(role);
        // 角色变了，旧令牌里带的角色就过期了，必须作废重签
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);

        auditService.record(adminId, AdminAuditService.USER_CHANGE_ROLE,
                AdminAuditService.TARGET_USER, userId,
                "账号 " + saved.getUsername() + " 的角色 " + before + " → " + role);

        log.info("admin_user_role adminId={} userId={} {} -> {}", adminId, userId, before, role);
        return AdminUserResponse.from(saved);
    }

    /**
     * 软删除。
     *
     * <p>软删除而不是物理删除：这个人的课件、问答记录、发言都通过外键指着他，
     * 真正 DELETE 会被外键约束挡住（或者连带删掉一堆历史数据）。
     * 标记 deleted 之后他就登不进来、也不再出现在列表里。
     */
    @Transactional
    public void remove(Long adminId, Long userId) {
        if (adminId.equals(userId)) {
            throw new BusinessException("不能删除自己的账号");
        }

        User user = requireUser(userId);
        if (user.getRole() == Role.ADMIN && isLastAdmin()) {
            throw new BusinessException("这是最后一个管理员，不能删除——删完就没人能进管理端了");
        }

        user.setDeleted(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        auditService.record(adminId, AdminAuditService.USER_DELETE,
                AdminAuditService.TARGET_USER, userId, "删除账号 " + user.getUsername());
        log.info("admin_user_deleted adminId={} userId={}", adminId, userId);
    }

    /** 现在只剩一个未删除的管理员了吗。 */
    private boolean isLastAdmin() {
        return userRepository.countByRoleAndDeletedFalse(Role.ADMIN) <= 1;
    }

    /** 严格解析角色。取值非法时明确报错，而不是静默当成学生。 */
    private Role parseRole(String value) {
        if (value == null) {
            throw new BusinessException("角色不能为空");
        }
        try {
            return Role.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("角色取值不合法：" + value + "（应为 TEACHER / STUDENT / ADMIN）");
        }
    }

    /** 筛选条件里的角色：不合法就当作「没有这个筛选」，不报错——筛选条件不该让列表打不开。 */
    private Role parseRoleOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Role.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
