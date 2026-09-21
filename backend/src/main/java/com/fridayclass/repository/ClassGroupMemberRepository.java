package com.fridayclass.repository;

import com.fridayclass.entity.ClassGroupMember;
import com.fridayclass.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 班级成员数据访问接口。
 *
 * <p>所有查询都 {@code join fetch m.user}：名单要显示学生昵称，
 * 而 {@code open-in-view=false}，事务外读懒加载关联会抛 {@code LazyInitializationException}。
 */
public interface ClassGroupMemberRepository extends JpaRepository<ClassGroupMember, Long> {

    /** 某个班的成员名单（带学生信息）。 */
    @Query("""
            select m from ClassGroupMember m
            join fetch m.user
            where m.classGroup.id = :groupId
            order by m.id desc
            """)
    List<ClassGroupMember> findByGroupWithUser(@Param("groupId") Long groupId);

    /** 某班成员数。 */
    long countByClassGroupId(Long groupId);

    /** 判断某人是否已在这个班里（重复加人时给出明确提示，而不是撞唯一键报 500）。 */
    Optional<ClassGroupMember> findByClassGroupIdAndUserId(Long groupId, Long userId);

    /**
     * 一批班级的**全体成员 ID**（去重前）。
     *
     * <p>开课快照用它一次性取回所有选中班级的成员。
     * 合班时两班可能有共同的学生，去重由调用方做（或直接依赖
     * {@code session_audience} 的唯一键）。
     */
    @Query("""
            select m.user.id from ClassGroupMember m
            where m.classGroup.id in :groupIds
            """)
    List<Long> findUserIdsByClassGroupIds(@Param("groupIds") Collection<Long> groupIds);

    /**
     * 我所在的全部班级（学生端）。
     *
     * <p>{@code join fetch m.classGroup g} 同时预取班级，且过滤掉已软删的班
     * —— 被软删的班不该再出现在学生的「我的班级」里。
     */
    @Query("""
            select m from ClassGroupMember m
            join fetch m.classGroup g
            where m.user.id = :userId and g.deleted = false
            order by m.id desc
            """)
    List<ClassGroupMember> findMyGroups(@Param("userId") Long userId);

    /** 某个班的全员（含已软删的学生）—— 仅供「该不该把班删掉」这类内部判断使用。 */
    @Query("select m.user.id from ClassGroupMember m where m.classGroup.id = :groupId")
    List<Long> findUserIdsByGroupId(@Param("groupId") Long groupId);

    /**
     * 这位教师**名下班级里的全部学生**（去重），供开课弹窗的「单独勾选同学」搜索。
     *
     * <p><b>为什么只搜「我班里的学生」，而不是全校学生</b>：
     * 搜全校意味着任何一位教师打开一次开课弹窗，就能拉到整份学生名册
     * （用户名 + 昵称）。这个接口没有任何场景需要那么多数据 ——
     * 「选完班级后追加个别学生」要追加的人，本来就在自己的班里。
     *
     * <p>代价是：**还没有建过班的教师无法单独选人**，得先建班。
     * 这是刻意的取舍（拒绝优先、最小暴露），如果确实需要放开，
     * 把这个查询的 where 改掉即可，但那是扩大数据暴露面的决定，要显式做。
     *
     * <p>⚠ <b>为什么要把 user 显式 {@code join m.user u} 出来再排序</b>：
     * 写成 {@code select distinct m.user ... order by m.user.id} 会在 MySQL 上直接报
     * <i>3065 Expression #1 of ORDER BY clause is not in SELECT list ... incompatible
     * with DISTINCT</i> —— Hibernate 把 {@code m.user.id} 翻译成外键列
     * {@code class_group_member.user_id}，而 DISTINCT 要求 ORDER BY 的表达式必须在
     * SELECT 列表里。别名到被选中的实体上（{@code order by u.id}）就不会了。
     * 这个错**启动时查不出来**（JPQL 解析没问题），只有真正跑这条 SQL 才炸。
     */
    @Query("""
            select distinct u from ClassGroupMember m
            join m.user u
            join m.classGroup g
            where g.teacher.id = :teacherId
              and g.deleted = false
              and u.deleted = false
              and u.disabled = false
              and (:hasKeyword = false
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.nickname) like lower(concat('%', :keyword, '%')))
            order by u.id desc
            """)
    List<User> findMyStudents(@Param("teacherId") Long teacherId,
                              @Param("hasKeyword") boolean hasKeyword,
                              @Param("keyword") String keyword,
                              Pageable pageable);
}
