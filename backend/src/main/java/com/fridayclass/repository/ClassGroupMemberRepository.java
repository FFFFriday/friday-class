package com.fridayclass.repository;

import com.fridayclass.entity.ClassGroupMember;
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
}
