package com.fridayclass.entity;

import com.fridayclass.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户实体。对应 user 表（角色：TEACHER 教师 / STUDENT 学生）。
 *
 * <p><b>注意：这里故意不加 {@code @SQLRestriction("deleted = 0")}。</b>
 * User 经常作为被 join 的一方（课件→上传者、问答→学生、课堂→教师）。
 * 把软删除条件挂在实体上会被追加进 join 条件：左连接时行被滤成 NULL，
 * 而外键列仍有值，Hibernate 会创建代理，一旦访问关联就抛
 * {@code ObjectRetrievalFailureException}（实体不存在）。
 * 因此软删除过滤改用 Repository 的显式方法名
 * （{@code findByUsernameAndDeletedFalse} 等），语义清晰且不影响 join。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(length = 50)
    private String nickname;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Boolean deleted = false;

    /** 令牌版本：改密码时自增，使此前签发的 JWT 立即失效（无状态 JWT 的撤销手段）。 */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;
}
