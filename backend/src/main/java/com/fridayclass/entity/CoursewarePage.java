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
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 课件页实体。对应 courseware_page 表。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "courseware_page")
public class CoursewarePage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属课件。
     *
     * <p><b>{@code @NotFound(IGNORE)} 必需</b>：课件走软删除，而删课件时<b>不</b>清理本表，
     * 所以库里存在大量「页还在、指向的课件已软删」的行（2026-09-22 实测 575 行）。
     * 没有这个注解，读到这类行时访问 {@code courseware} 会抛
     * {@code ObjectRetrievalFailureException}，整个接口 500。
     *
     * <p>调用方本来就写了 {@code page.getCourseware() == null} 的判空
     * （{@code ChatService#resolvePage}、{@code QaService}）——写代码的人以为它会是 null，
     * 但在没有注解时那个判空是<b>永远走不到的</b>。加注解才让它真正生效。
     * 详细机制见 {@link ClassSession#getCourseware()}。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courseware_id", nullable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Courseware courseware;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "slide_url", length = 500)
    private String slideUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
