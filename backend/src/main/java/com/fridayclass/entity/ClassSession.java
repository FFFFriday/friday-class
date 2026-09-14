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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id", nullable = false)
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Boolean deleted = false;
}
