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

import java.time.LocalDateTime;

/**
 * 课堂 ↔ 班级 关联（一节课可以同时面向多个班 = 合班上课）。
 * 对应 {@code session_class_group} 表。
 *
 * <p><b>为什么是关联表而不是在 {@code class_session} 上加一列 {@code class_group_id}</b>：
 * 加一列只能表达「这节课属于哪个班」，是单数；而合班是「一节课 → N 个班」，
 * 是标准的多对多，只能靠关联表。
 *
 * <p><b>本表不参与授权判定。</b> 授权只看 {@link SessionAudience} ——
 * 那张表在开课时就把各班级成员摊平进去了，所以「合班」在授权逻辑里
 * 根本不存在特例。本表只用于两件事：
 * <ol>
 *   <li>展示：学生端/教师端显示「这节课是给哪几个班上的」；</li>
 *   <li>查询：教师端「这个班都上过哪些课」（靠 {@code idx_scg_group}）。</li>
 * </ol>
 *
 * <p>本实体指向的 {@link ClassSession} 与 {@link ClassGroup} 都不带
 * {@code @SQLRestriction}（都是刻意为之，理由见各自注释），所以这里不需要 {@code @NotFound}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_class_group")
public class SessionClassGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id", nullable = false)
    private ClassGroup classGroup;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
