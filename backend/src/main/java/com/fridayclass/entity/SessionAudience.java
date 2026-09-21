package com.fridayclass.entity;

import com.fridayclass.enums.AudienceSource;
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

import java.time.LocalDateTime;

/**
 * 课堂听课名单。<b>这是「谁能看这节课」的唯一依据。</b>
 * 对应 {@code session_audience} 表。
 *
 * <h3>为什么是「开课瞬间的快照」，而不是每次实时查班级成员</h3>
 *
 * <table>
 *   <tr><td>判定收口到一处</td><td>「这个学生能不能看这堂课」= 他在这张表里有没有行，
 *       一行 SQL 搞定，不查班级、不查关联表</td></tr>
 *   <tr><td>不受中途换班影响</td><td>上课期间把某人移出班级，不该让他当场掉线；反之亦然</td></tr>
 *   <tr><td>历史可追溯</td><td>课后回顾时，名单就是<b>当时真实被授权的人</b>，
 *       不会因为后来的班级变动被改写</td></tr>
 *   <tr><td>合班天然支持</td><td>两个班的成员并集摊平写进来，唯一键负责去重——
 *       「合班」因此在授权层面<b>根本不是一个特殊情况</b></td></tr>
 * </table>
 *
 * <p>⚠ 不要拿 {@code session_participant} 冒充本表：那是**课后**写的「谁真的进过这节课」，
 * 用于统计。用它做课前授权，结果是「第一个进来的学生反而进不来」。
 *
 * <p>本实体指向的 {@link ClassSession} 已被刻意去掉 {@code @SQLRestriction}
 * （2026-09-22，理由见其注释），所以这里不需要 {@code @NotFound}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_audience")
public class SessionAudience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 来源。仅用于展示与追溯，**不参与授权判定**。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AudienceSource source = AudienceSource.MANUAL;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
