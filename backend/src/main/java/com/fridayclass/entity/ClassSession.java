package com.fridayclass.entity;

import com.fridayclass.enums.SessionStatus;
import com.fridayclass.enums.SessionVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 课堂/直播会话实体。对应 class_session 表。
 *
 * <p><b>注意：这里刻意<b>没有</b> {@code @SQLRestriction("deleted = 0")}（2026-09-22 移除）。</b>
 *
 * <p>理由与 {@link User} 完全一致 —— {@code ClassSession} 是**被 join 的一方**：
 * {@code ChatMessage}、{@code QaRecord}、{@code SessionParticipant}、{@code CourseSummary}、
 * {@code AiConversation} 五个实体都有指向它的 {@code @ManyToOne}。
 * 把软删条件挂在实体上会被追加进 join 条件，行被滤掉而外键仍有值，
 * Hibernate 就判定「数据坏了」并抛 {@code FetchNotFoundException} /
 * {@code ObjectRetrievalFailureException} —— 2026-09-22 管理端两个页面打不开，
 * 正是 {@code Courseware} 上同一颗雷炸了（见 {@code X2} 分册）。
 *
 * <p>而移除它在这里是**零风险**的，因为：
 * <ol>
 *   <li>全项目**没有任何一处**软删课堂 —— 没有 {@code setDeleted(true)} 的调用，
 *       实测 {@code class_session} 里 {@code deleted = 1} 的行数为 <b>0</b>；</li>
 *   <li>因此这个注解今天**过滤不掉任何一行**，它唯一的作用就是埋雷。</li>
 * </ol>
 *
 * <p>这与 {@code Courseware} 上「保留 {@code @SQLRestriction}、改用 {@code @NotFound}」
 * 是**两个不同处境下的不同解法**，不要互相套用：
 * {@code Courseware} 有 10 个 Service 依赖它做过滤（删掉会漏出已删课件），
 * {@code ClassSession} 则没有任何依赖。
 *
 * <p>{@code deleted} 列本身保留 —— 将来若真要软删课堂，请连同
 * {@code ChatMessage#session} 等五处关联一起处理，判据见 {@code AiConversation#session}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "class_session")
public class ClassSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 授课所用课件。
     *
     * <p><b>{@code @NotFound(IGNORE)} 是必需的，不是可选优化。</b>
     * {@link Courseware} 上带 {@code @SQLRestriction("deleted = 0")}，而课件走的是软删除。
     * 于是「这节课用的课件被删了」会形成一个**外键有值、但行被过滤掉**的状态。
     * 没有这个注解时 Hibernate 的判定是「数据坏了」，直接抛
     * {@code FetchNotFoundException}（join fetch 时）或
     * {@code ObjectRetrievalFailureException}（访问懒加载代理时），
     * <b>整个列表接口 500</b>——2026-09-22 管理端「概览」「课堂管理」两页打不开就是这么来的：
     * 课堂 11 引用了已软删的课件 32，`GET /api/admin/sessions` 全量查询必然踩中。
     *
     * <p>加上之后语义变成「取不到就当 null」，与 DTO 层的兜底一致
     * （{@code SessionResponse.from} 会把课件名显示成「已删除」）。
     * 注意：这与 {@link User} 上「干脆不加 {@code @SQLRestriction}」是两种不同的解法，
     * 各自成立——User 是不需要过滤，Courseware 是需要过滤但不能让 join 崩。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Courseware courseware;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @Column(length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.NOT_STARTED;

    /**
     * 可见性。决定「谁能看到这节课」，判定口径见 {@code SessionAccessService}。
     *
     * <p><b>默认 {@code RESTRICTED}（拒绝优先）</b>，与数据库列默认值一致。
     * 具体谁能看，由 {@code session_audience} 开课快照决定，本字段只是总开关。
     *
     * <p>V3 迁移（2026-09-22）之前就存在的历史课堂，已统一回填成 {@code PUBLIC}，
     * 所以升级后学生端不会出现「历史记录突然全消失」。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionVisibility visibility = SessionVisibility.RESTRICTED;

    @Column(name = "stream_push_url", length = 500)
    private String streamPushUrl;

    @Column(name = "stream_pull_url", length = 500)
    private String streamPullUrl;

    /**
     * 当前页码。翻页时**先写这里再广播**，供迟到/断线重连的学生恢复上下文。
     *
     * <p>这一列在 {@code db/schema.sql} 里早就建好了，但实体一直没映射，
     * 导致 F003 的「先落库再广播」实际落不进去（值永远是 NULL）。补上。
     */
    @Column(name = "current_page")
    private Integer currentPage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** 暂停时间。NULL = 未暂停。恢复时清回 NULL。 */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /**
     * 结束人。NULL = 老师自己下课；非空 = 被管理员强制下课。
     * 存裸 ID 而不是 {@code @ManyToOne User}，理由见 {@code ChatMessage#deletedBy}：
     * 「谁干的」这类追溯字段，写入方只需一个数字。
     */
    @Column(name = "ended_by")
    private Long endedBy;

    /** 结束原因，如 {@code ADMIN_FORCE}。老师自己下课时为 NULL。 */
    @Column(name = "end_reason", length = 100)
    private String endReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Boolean deleted = false;
}
