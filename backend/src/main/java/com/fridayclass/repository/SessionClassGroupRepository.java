package com.fridayclass.repository;

import com.fridayclass.entity.SessionClassGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 课堂 ↔ 班级 关联数据访问接口（合班上课）。
 *
 * <p>注意：**本表不参与授权判定**，只用于展示「这节课是给哪几个班上的」
 * 和「这个班都上过哪些课」。授权只看 {@code session_audience}。
 */
public interface SessionClassGroupRepository extends JpaRepository<SessionClassGroup, Long> {

    /**
     * 一批课堂各自面向的班级（带班级信息）。
     *
     * <p>批量取而不是逐个取：学生端/教师端的课堂列表要显示班级名，
     * 一页 20 条逐条查就是 20 次 SQL（N+1）。
     */
    @Query("""
            select s from SessionClassGroup s
            join fetch s.classGroup
            where s.session.id in :sessionIds
            order by s.id asc
            """)
    List<SessionClassGroup> findBySessionIdsWithGroup(@Param("sessionIds") Collection<Long> sessionIds);

    /** 某课堂面向的班级。 */
    @Query("""
            select s from SessionClassGroup s
            join fetch s.classGroup
            where s.session.id = :sessionId
            order by s.id asc
            """)
    List<SessionClassGroup> findBySessionWithGroup(@Param("sessionId") Long sessionId);

    /** 某个班开过多少节课（管理端班级列表的「已开课数」）。 */
    long countByClassGroupId(Long classGroupId);
}
