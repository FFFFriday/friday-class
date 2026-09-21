package com.fridayclass.entity;

import com.fridayclass.enums.AiTaskStatus;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 课件解析任务实体。对应 ai_parse_task 表（异步逐页解析的进度跟踪）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_parse_task")
public class AiParseTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 待解析的课件。{@code @NotFound(IGNORE)} 理由见 {@link CoursewarePage#getCourseware()}：
     * 课件软删除后本行会指向一个读不出来的课件，不加注解读取即 500。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Courseware courseware;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiTaskStatus status = AiTaskStatus.PENDING;

    @Column(name = "current_page", nullable = false)
    private Integer currentPage = 0;

    @Column(name = "total_pages")
    private Integer totalPages;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
