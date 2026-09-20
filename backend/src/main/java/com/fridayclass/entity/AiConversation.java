package com.fridayclass.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 学生 AI 会话。对应 ai_conversation 表。
 *
 * <p>这是「学生的 AI 不是每次登录都是新的」这条需求的落点：
 * 会话归属学生本人，可以建多个、随时切换，每个会话各自保留上下文。
 *
 * <p><b>会话之间上下文完全隔离</b>——互不引用、互不污染。
 * {@code courseware} / {@code session} 只是「创建时的来源标记」，
 * 用于筛选和归档，**不作为上下文边界**：同一份课件下开的两个会话照样互不可见。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_conversation")
@SQLRestriction("deleted = 0")
public class AiConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Column(nullable = false, length = 100)
    private String title = "新会话";

    /** 关联课件，可空（空 = 自由问答，不挂任何课件）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id")
    private Courseware courseware;

    /** 创建时所处的课堂，可空（空 = 课后创建）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ClassSession session;

    /**
     * 最后活跃时间。会话列表按它倒序——不是按创建时间，
     * 否则「刚聊过的会话」会被挤到下面去。
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(nullable = false)
    private Boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
