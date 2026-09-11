package com.fridayclass.repository;

import com.fridayclass.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户数据访问接口。
 *
 * <p>软删除过滤走显式方法名（{@code ...AndDeletedFalse}），而不是实体上的
 * {@code @SQLRestriction}：User 常作为 join 目标，全局限制会导致
 * 「外键有值但关联行被滤掉」而抛 ObjectRetrievalFailureException。
 * 详见 {@link com.fridayclass.entity.User} 的类注释。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按用户名查未删除的用户。登录与令牌校验必须走这个。 */
    Optional<User> findByUsernameAndDeletedFalse(String username);

    /** 用户名是否已被未删除的用户占用（注册查重用）。 */
    boolean existsByUsernameAndDeletedFalse(String username);
}
