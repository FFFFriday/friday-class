package com.fridayclass.entity;

import com.fridayclass.enums.Role;
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
 * 课堂参与记录。对应 session_participant 表。
 *
 * <p>一个用户在一节课里**只有一行**（{@code uk_part_session_user} 唯一键），
 * 反复进出只更新 {@code leftAt} / {@code lastSeenAt}，不新增行。
 *
 * <p>一行数据同时支撑三处：课堂实时在线名单、管理端的在线人数与踢人、
 * 以及课堂记录里「哪些学生来过」。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_participant")
public class SessionParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 进入时的角色快照。存快照而不是每次 join user 表取当前角色：
     *  管理员事后改了某人的角色，历史出席记录不该跟着变。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    /** 离开时间。NULL = 仍在场。 */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    /** 最后心跳时间，用于判定「连接断了但没发离开消息」的掉线。 */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
