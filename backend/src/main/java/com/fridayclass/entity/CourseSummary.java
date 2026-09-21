package com.fridayclass.entity;

import com.fridayclass.enums.SummaryStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.LocalDateTime;

/**
 * 课程总结实体。对应 course_summary 表（一个课堂唯一一条总结）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_summary")
public class CourseSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private ClassSession session;

    /**
     * 总结所依据的课件。{@code @NotFound(IGNORE)} 理由见 {@link CoursewarePage#getCourseware()}。
     * 调用方 {@code SessionSummaryService} 已写了 {@code getCourseware() == null} 判空。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Courseware courseware;

    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 生成状态。总结是异步生成的（一次几十秒），接口不能挂着干等，
     * 所以先落一条 PENDING 立刻返回，前端轮询，好了再取正文。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SummaryStatus status = SummaryStatus.SUCCESS;

    /** 失败原因。仅有当 {@code status = FAILED} 时有值。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 总结来源。本次只做 {@code SESSION_CHAT}（<b>仅基于聊天记录</b>）。
     *
     * <p>刻意用 String 而不是建一个只有一个值的枚举：这是个预留的扩展位，
     * 将来若要「课件 + 知识点 + 问答全量总结」再补第二个值，
     * 现在就建枚举属于过早抽象。
     */
    @Column(nullable = false, length = 20)
    private String source = "SESSION_CHAT";

    @CreationTimestamp
    @Column(name = "generated_at", nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
