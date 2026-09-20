package com.fridayclass.entity;

import com.fridayclass.enums.ChatMessageStatus;
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
 * 课堂讨论区发言。对应 chat_message 表。
 *
 * <p>{@code content} 是 <b>纯文本</b>，前端用文本插值渲染、绝不 v-html。
 * 一旦允许富文本，学生就能互相注入脚本。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 发言时所在页。可空——学生可能在任何时刻发言，
     * 而且这个值只是给「课堂记录」提供页面上下文，不该成为发言的前置条件。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id")
    private CoursewarePage page;

    @Column(nullable = false, length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageStatus status = ChatMessageStatus.NORMAL;

    /**
     * 删除人。存裸 ID 而不是 {@code @ManyToOne User}：这是「谁干的」这类追溯字段，
     * 写入方只需一个数字，不该为了记一笔日志去查一次用户表。
     * 本工程统一此规则——{@code class_session.ended_by} 同理。
     */
    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 发言时间。
     *
     * <p>这里用 {@code @CreationTimestamp} 而不是像 {@code qa_record.asked_at} 那样
     * 手工截断到整秒，是因为两者的用途不同：{@code asked_at} 是学情统计的依据、
     * 要求「返回的值 == 库里的值」；而讨论区的顺序**按 id 排**（自增主键，
     * 天然严格有序），时间只用于显示到分钟。DATETIME(0) 的四舍五入最多差 1 秒，
     * 显示上看不出来，换来的是「不会忘记赋值」。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
