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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 班级实体。对应 {@code class_group} 表。
 *
 * <p>一个班由一位教师创建（{@code teacher}），成员是一群学生，可增可减。
 * <b>一个学生可以同时属于多个班</b> —— 唯一键是「班级 + 人」而不是「人」，见
 * {@link ClassGroupMember}。
 *
 * <p><b>注意：这里刻意<b>没有</b> {@code @SQLRestriction("deleted = 0")}。</b>
 * {@code ClassGroupMember.classGroup} 与 {@code SessionClassGroup.classGroup}
 * 都指向本实体（本实体是**被 join 的一方**）。把软删条件挂在实体上会被追加进
 * join 条件，一旦班级被软删、而成员行还指着它，Hibernate 就会抛
 * {@code FetchNotFoundException} 而不是给出 null ——
 * {@code Courseware} 上同一颗雷在 2026-09-22 炸掉了管理端两个页面。
 *
 * <p>所以过滤改用 Repository 的显式方法名（{@code ...AndDeletedFalse}），
 * 与 {@link User} 的做法一致，语义清楚且不牵连 join。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "class_group")
public class ClassGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 建班教师（班主任）。教师只能操作自己建的班，管理员不受限。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private Boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
