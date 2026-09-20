package com.fridayclass.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 管理操作审计日志。对应 admin_audit_log 表。
 *
 * <p>只做「写 + 查」，不配统计图表（按 Friday 指示）。
 *
 * <p>{@code adminId} / {@code targetId} 都存裸 ID 而不是 {@code @ManyToOne}：
 * 审计日志是**只增不改的流水**，它的价值在于「当时发生了什么」这个事实本身。
 * 用外键关联会在被关联对象将来被删除时把人卡住（删不掉用户），
 * 而审计日志恰恰应该在那之后依然留存。库里的 FK 只做完整性兜底，
 * 实体层不建立关联，查询时按需批量取名字即可。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人（管理员）用户 ID。 */
    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    /** 动作码，如 {@code SESSION_FORCE_END}。取值约定见审计动作码表。 */
    @Column(nullable = false, length = 50)
    private String action;

    /** 对象类型：USER / SESSION / COURSEWARE / STORAGE。 */
    @Column(name = "target_type", length = 30)
    private String targetType;

    /** 对象 ID。批量操作时可为空（细节看 {@code detail}）。 */
    @Column(name = "target_id")
    private Long targetId;

    /** 细节描述。 */
    @Column(length = 500)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
