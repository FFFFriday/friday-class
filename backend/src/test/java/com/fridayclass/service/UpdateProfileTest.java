package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.UpdateProfileRequest;
import com.fridayclass.dto.UserResponse;
import com.fridayclass.entity.User;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.security.JwtService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「改昵称」的单元测试 —— 对应 {@code PUT /api/auth/profile}。
 *
 * <p>为什么要专门测这个看起来很简单的方法：
 * <ol>
 *   <li>它的约束全在**注解**里（{@code @NotBlank} / {@code @Size}），
 *       而注解改了不会有任何编译期反馈 —— 只能靠测试锁住。</li>
 *   <li>它和同类的 {@code changePassword} 有一处**故意的差异**：
 *       改密码会把 {@code tokenVersion} +1 踢掉所有旧 JWT，改昵称**不能**这么做。
 *       这条差异一旦被"顺手统一"掉，症状是「改个名字就被登出」，很难联想到原因。</li>
 * </ol>
 *
 * <p>不启 Spring 上下文：校验用 Hibernate Validator 直接跑，
 * Service 用 Mock 的仓库 —— 与项目里其它测试（全部是纯 JUnit）保持一致，也跑得快。
 */
@DisplayName("改昵称（PUT /api/auth/profile）")
class UpdateProfileTest {

    @Nested
    @DisplayName("请求参数校验")
    class 参数校验 {

        private static ValidatorFactory factory;
        private static Validator validator;

        @BeforeAll
        static void openValidator() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void closeValidator() {
            factory.close();
        }

        private Set<ConstraintViolation<UpdateProfileRequest>> violationsOf(String nickname) {
            return validator.validate(new UpdateProfileRequest(nickname));
        }

        @Test
        @DisplayName("正常昵称通过")
        void normal() {
            assertTrue(violationsOf("张三").isEmpty());
        }

        @Test
        @DisplayName("刚好 50 字通过（库列就是 VARCHAR(50)，这是上边界）")
        void exactlyFifty() {
            assertTrue(violationsOf("字".repeat(50)).isEmpty());
        }

        @Test
        @DisplayName("51 字被拒，且提示里写明上限")
        void fiftyOne() {
            Set<ConstraintViolation<UpdateProfileRequest>> v = violationsOf("字".repeat(51));
            assertEquals(1, v.size());
            assertTrue(v.iterator().next().getMessage().contains("50"), v.toString());
        }

        @Test
        @DisplayName("null 被拒")
        void isNull() {
            assertFalse(violationsOf(null).isEmpty());
        }

        @Test
        @DisplayName("空串被拒")
        void empty() {
            assertFalse(violationsOf("").isEmpty());
        }

        @Test
        @DisplayName("纯空白被拒 —— 这正是 @NotBlank 相对 @NotNull 的全部价值")
        void blankOnly() {
            assertFalse(violationsOf("   ").isEmpty());
        }

        @Test
        @DisplayName("首尾带空格但中间有字 → 通过（trim 是 Service 的职责，不是校验的）")
        void padded() {
            assertTrue(violationsOf("  张三  ").isEmpty());
        }
    }

    @Nested
    @DisplayName("Service 行为")
    class 改昵称 {

        private static final Long USER_ID = 7L;

        private UserRepository userRepository;
        private AuthService authService;
        private User user;

        @BeforeEach
        void setUp() {
            userRepository = mock(UserRepository.class);
            authService = new AuthService(
                    userRepository, mock(PasswordEncoder.class), mock(JwtService.class));

            user = new User();
            user.setId(USER_ID);
            user.setUsername("zhangsan");
            user.setNickname("旧名字");
            user.setTokenVersion(3);
            // role 必须设：UserResponse.from 会调 getRole().name()。
            // 库里的 role 是 NOT NULL 列，所以这只是测试夹具的问题，不是生产的空指针风险。
            user.setRole(Role.STUDENT);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("首尾空格被去掉后再入库")
        void trimsBeforeSaving() {
            UserResponse saved = authService.updateProfile(USER_ID, new UpdateProfileRequest("  新名字  "));

            assertEquals("新名字", saved.nickname());
            assertEquals("新名字", user.getNickname());
        }

        @Test
        @DisplayName("★ 不改 tokenVersion —— 改显示名不该把当前登录踢下线")
        void keepsTokenVersion() {
            authService.updateProfile(USER_ID, new UpdateProfileRequest("新名字"));

            assertEquals(3, user.getTokenVersion().intValue());
        }

        @Test
        @DisplayName("username 一个字都不动（它是登录凭据）")
        void usernameUntouched() {
            UserResponse saved = authService.updateProfile(USER_ID, new UpdateProfileRequest("新名字"));

            assertEquals("zhangsan", saved.username());
            assertEquals("zhangsan", user.getUsername());
        }

        @Test
        @DisplayName("返回体就是改完之后的用户信息（前端拿它直接刷新登录态）")
        void returnsUpdatedUser() {
            UserResponse saved = authService.updateProfile(USER_ID, new UpdateProfileRequest("新名字"));

            assertEquals(USER_ID, saved.id());
            assertEquals("新名字", saved.nickname());
        }

        @Test
        @DisplayName("用户不存在 → 404 业务异常")
        void userMissing() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.updateProfile(404L, new UpdateProfileRequest("新名字")));
            assertEquals(404, ex.getCode());
        }
    }
}
