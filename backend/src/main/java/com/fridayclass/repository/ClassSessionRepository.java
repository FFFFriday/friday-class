package com.fridayclass.repository;

import com.fridayclass.entity.ClassSession;
import com.fridayclass.enums.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     *       课件被软删除时关联取不到行。依赖 {@code ClassSession.courseware} 上的
     *       {@code @NotFound(IGNORE)} 退化为 null，DTO 里已做兜底。</li>
     * </ol>
     *
     * <p><b>注意「left join fetch 会退化为 null」这句话原本是错的。</b>
     * 在本仓库 2026-09-22 修复之前，{@code Courseware} 上有 {@code @SQLRestriction}
     * 而关联上没有 {@code @NotFound}，被过滤掉的行会让 Hibernate 抛
     * {@code FetchNotFoundException}——{@code left} 与否完全不影响，
     * 因为异常发生在实体装配阶段，不是 SQL 阶段。
     * 七个方法都信了这句话，于是都埋着同一颗雷（头一个炸的是 {@code searchForAdmin}，
     * 也正是管理端两个页面打不开的原因）。
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

    // ── 管理端（M6） ───────────────────────────────────────────

    /**
     * 管理端的全量课堂检索：状态、教师、关键字三个条件都可选。
     *
     * <p>用 {@code :hasXxx} 布尔开关而不是 {@code :xxx is null}，
     * 理由同 {@code UserRepository.searchForAdmin}（Hibernate 6 对可空枚举参数
     * 做 is-null 判断需要显式类型提示）。
     *
     * <p>{@code join fetch} 是必需的：列表要显示课件名与教师名，
     * 而 {@code open-in-view=false}，事务外读懒加载关联会抛异常。
     * 两个都是 to-one，所以配合分页不会退化成内存分页。
     */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where (:hasStatus = false or s.status = :status)
              and (:hasTeacher = false or s.teacher.id = :teacherId)
              and (:hasKeyword = false or lower(s.title) like lower(concat('%', :keyword, '%')))
            order by s.id desc
            """)
    Page<ClassSession> searchForAdmin(@Param("hasStatus") boolean hasStatus,
                                      @Param("status") SessionStatus status,
                                      @Param("hasTeacher") boolean hasTeacher,
                                      @Param("teacherId") Long teacherId,
                                      @Param("hasKeyword") boolean hasKeyword,
                                      @Param("keyword") String keyword,
                                      Pageable pageable);

    /** 按状态批量取（「暂停所有课堂」这类批量操作用）。 */
    List<ClassSession> findByStatus(SessionStatus status);

    /** 按一组 ID 取，带课件与教师（批量操作后组装响应）。 */
    @Query("""
            select s from ClassSession s
            left join fetch s.courseware
            left join fetch s.teacher
            where s.id in :ids
            """)
    List<ClassSession> findAllWithDetailByIdIn(@Param("ids") Collection<Long> ids);
}
