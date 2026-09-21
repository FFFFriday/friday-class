package com.fridayclass.repository;

import com.fridayclass.entity.SessionAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 课堂听课名单数据访问接口。
 *
 * <p><b>本接口的 {@link #existsBySessionIdAndUserId} 是整个可见性体系的核心一行</b> ——
 * 所有「这个学生能不能看这节课」的判定都收口到它，见 {@code SessionAccessService}。
 * 唯一键 {@code uk_audience_session_user} 让这个查询直接命中索引，
 * 不需要扫表、也不需要 JOIN 班级表。
 */
public interface SessionAudienceRepository extends JpaRepository<SessionAudience, Long> {

    /**
     * 这堂课有没有授权给这个学生。
     *
     * <p>刻意返回 {@code boolean} 而不是 Optional / 实体：调用方只关心「在不在」，
     * 返回实体反而诱导调用方去做多余的判空。
     */
    boolean existsBySessionIdAndUserId(Long sessionId, Long userId);

    /** 某课堂的听课名单（带学生信息，供展示「参与名单」）。 */
    @Query("""
            select a from SessionAudience a
            join fetch a.user
            where a.session.id = :sessionId
            order by a.id asc
            """)
    List<SessionAudience> findBySessionWithUser(@Param("sessionId") Long sessionId);

    /** 这堂课被授权的人数。 */
    long countBySessionId(Long sessionId);

    /**
     * 某个学生被授权过的全部课堂 ID（学生端「我的课堂」的查询入口）。
     *
     * <p>只返回 ID 而不是实体：调用方随后要按状态、时间排序并分页，
     * 在 SQL 里一次查完比先捞一批实体再在内存里筛更省。
     */
    @Query("""
            select a.session.id from SessionAudience a
            where a.user.id = :userId
            """)
    List<Long> findVisibleSessionIds(@Param("userId") Long userId);
}
