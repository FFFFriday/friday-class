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
 * 班级成员。对应 {@code class_group_member} 表。
 *
 * <p><b>唯一键是「班级 + 人」（{@code uk_member_group_user}），不是「人」。</b>
 * 这就是「一个学生能同时属于多个班」的落点：同一个人在不同班里各占一行，互不干扰。
 * 若把唯一键错设成 {@code user_id}，一个学生就只能待在一个班了。
 *
 * <p>本实体指向的两个实体都不能加 {@code @SQLRestriction}：
 * {@link ClassGroup} 是刻意不加（理由见其注释），
 * {@link User} 也是刻意不加（理由见其注释）。
 * 所以这里不需要 {@code @NotFound}。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "class_group_member")
public class ClassGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id", nullable = false)
    private ClassGroup classGroup;

    /** 成员。业务上只放学生，教师账号不该被加进班级名单。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;
}
