package com.fridayclass.entity;

import com.fridayclass.enums.SessionStatus;
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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 课堂/直播会话实体。对应 class_session 表。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "class_session")
@SQLRestriction("deleted = 0")
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
