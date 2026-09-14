package com.fridayclass.repository;

import com.fridayclass.entity.ClassSession;
import com.fridayclass.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 课堂/直播会话数据访问接口。
 */
public interface ClassSessionRepository extends JpaRepository<ClassSession, Long> {

    List<ClassSession> findByTeacherIdOrderByCreatedAtDesc(Long teacherId);

    /**
     * 课堂详情：同时取出课件与教师。
     *
     * <p>必须 left join fetch 而不是用 {@code findById}，两个理由：
     * <ol>
     *   <li>{@code open-in-view=false}，事务外访问懒加载关联会抛
     *       {@code LazyInitializationException}，而组装 DTO 要读
     *       {@code courseware.name} 与 {@code teacher.nickname}；</li>
     *   <li>{@code Courseware} 上带 {@code @SQLRestriction("deleted = 0")}，
     *       课件被软删除时懒加载代理会取不到行、抛
     *       {@code ObjectRetrievalFailureException}（整个接口 500）。
     *       用 <b>left</b> join fetch 则退化为关联为 null，DTO 里已做兜底。</li>
     * </ol>
     */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where s.id = :id
            """)
    Optional<ClassSession> findDetailById(@Param("id") Long id);

    /**
     * 按状态取课堂列表（门户「正在直播」用）。
     * 同样 join fetch，理由见 {@link #findDetailById}。
     */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where s.status = :status
            order by s.startedAt desc, s.id desc
            """)
    List<ClassSession> findByStatusWithDetail(@Param("status") SessionStatus status);
}
