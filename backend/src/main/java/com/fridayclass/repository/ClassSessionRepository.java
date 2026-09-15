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

    /**
     * 某位教师对某份课件**尚未结束**的课堂（含 {@code NOT_STARTED} 与 {@code LIVE}）。
     *
     * <p>开课接口靠它做幂等：老师从课件详情页再点一次「开始上课」，或者刷新页面后
     * 重新走一遍，都不该新建一节课——那会让老师「回不到自己刚开的那节课」。
     *
     * <p>返回 List 而不是 Optional：JPQL 没有 LIMIT，历史数据里万一同一位老师对同一份
     * 课件留有多条未结束记录（例如并发点击），返回 Optional 会直接抛
     * {@code IncorrectResultSizeDataAccessException}。调用方取第一条即可。
     */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where s.teacher.id = :teacherId
              and s.courseware.id = :coursewareId
              and s.status <> com.fridayclass.enums.SessionStatus.ENDED
            order by s.id desc
            """)
    List<ClassSession> findActiveByTeacherAndCourseware(@Param("teacherId") Long teacherId,
                                                        @Param("coursewareId") Long coursewareId);

    /**
     * 某位教师名下**所有未结束**的课堂（教师首页「我的课堂」用）。
     *
     * <p>与 {@link #findByStatusWithDetail} 的关键区别：那个只查 {@code LIVE}，
     * 是给学生看「现在有什么课」的；这个必须**包含 {@code NOT_STARTED}**，
     * 否则老师刚开完课、还没翻第一页就退出，就再也找不回自己的课堂了。
     */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where s.teacher.id = :teacherId
              and s.status <> com.fridayclass.enums.SessionStatus.ENDED
            order by s.id desc
            """)
    List<ClassSession> findMineWithDetail(@Param("teacherId") Long teacherId);
}
