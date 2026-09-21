package com.fridayclass.repository;

import com.fridayclass.entity.User;
import com.fridayclass.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 统计某角色的**未删除**用户数。用来防「把最后一个管理员删掉」。 */
    long countByRoleAndDeletedFalse(Role role);

    /**
     * 管理端用户检索：关键字（用户名/昵称）、角色、启用状态三个条件都可选。
     *
     * <p><b>为什么用 {@code :hasXxx} 布尔开关而不是 {@code :xxx is null}</b>：
     * Hibernate 6 对「可为空的**枚举**参数做 is-null 判断」需要显式类型提示，
     * 否则启动时就报无法推断参数类型。用布尔开关绕开这个问题，
     * 条件读起来也更直白——「有没有这个筛选条件」本来就是布尔语义。
     */
    @Query("""
            select u from User u
            where u.deleted = false
              and (:hasKeyword = false
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.nickname) like lower(concat('%', :keyword, '%')))
              and (:hasRole = false or u.role = :role)
              and (:hasDisabled = false or u.disabled = :disabled)
            order by u.id desc
            """)
    Page<User> searchForAdmin(@Param("hasKeyword") boolean hasKeyword,
                              @Param("keyword") String keyword,
                              @Param("hasRole") boolean hasRole,
                              @Param("role") Role role,
                              @Param("hasDisabled") boolean hasDisabled,
                              @Param("disabled") Boolean disabled,
                              Pageable pageable);

    /**
     * 可加入某个班级的学生（供班级管理页「加人」的搜索框）。
     *
     * <p>三件事都在 SQL 里做完，<b>都不交给前端</b>：
     * <ol>
     *   <li>只取 {@code STUDENT} 且未被禁用的账号 —— 教师账号不该被加进班级名单；</li>
     *   <li>关键字匹配用户名或昵称；</li>
     *   <li>{@code not exists} 排除**已经在这个班里**的人。</li>
     * </ol>
     * 第 3 条尤其不能下放给前端：那意味着未过滤的完整学生名单会先发到浏览器，
     * 任何教师打开一次加人弹窗就看得到全校学生。
     */
    @Query("""
            select u from User u
            where u.deleted = false
              and u.role = com.fridayclass.enums.Role.STUDENT
              and u.disabled = false
              and (:hasKeyword = false
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.nickname) like lower(concat('%', :keyword, '%')))
              and not exists (select 1 from ClassGroupMember m
                              where m.classGroup.id = :groupId and m.user.id = u.id)
            order by u.id desc
            """)
    Page<User> findStudentCandidates(@Param("groupId") Long groupId,
                                     @Param("hasKeyword") boolean hasKeyword,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);
}
