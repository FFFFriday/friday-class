package com.fridayclass.entity;

import com.fridayclass.enums.QaStatus;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 问答记录实体。对应 qa_record 表。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "qa_record")
public class QaRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    /**
     * 提问时所在页。
     *
     * <p><b>必须可空。</b>学生<b>下课后</b>问 AI 时不在任何一页上，没有 page_id 可填；
     * 这里先前是 {@code nullable = false}，会让「课后也能用 AI」这条需求
     * 直接卡死在落库这一步。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id")
    private CoursewarePage page;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    /**
     * 问答状态。列在 {@code db/schema.sql} 里一直都有，只是实体先前漏了映射
     * （见 API 接口文档 §6.1 的 P2）——不补上的话 F006 学情统计无法区分
     * 「答上了」与「调用失败」。
     *
     * <p>用 {@link EnumType#STRING} 而不是 ORDINAL：库里存的是
     * {@code 'SUCCESS'} 这样的字面量，且 ORDINAL 在枚举中间插一个值就会
     * 让所有历史数据错位。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QaStatus status = QaStatus.SUCCESS;

    /**
     * 幂等键，由客户端生成（UUID）。见 API 接口文档 §6.1 的 P3。
     *
     * <p>学生重复点「发送」（或网络重试）时，后端靠它认出「这是同一次提问」，
     * 直接返回上次的结果，不重复调模型、不重复入库。
     *
     * <p>允许为空：旧客户端 / 直接 curl 不带这个字段时退化为「无幂等」，
     * 而不是报错。唯一索引对 NULL 不生效（MySQL 语义），所以多条 NULL 可以共存。
     */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    /**
     * 所属 AI 会话。
     *
     * <p>老数据为 NULL <b>属正常</b>——它们产生于「会话管理」之前，没有会话可挂。
     * 课堂记录页会把它们归到「历史对话」下展示，不报错。
     */
    @Column(name = "conversation_id")
    private Long conversationId;

    /**
     * 所属课件（直连冗余列）。
     *
     * <p>没有它就只能从 {@code page_id} 两跳才能推出课件，而课后提问
     * 根本没有 page_id（见上），统计链会直接断掉。
     */
    @Column(name = "courseware_id")
    private Long coursewareId;

    /**
     * 提问时间。<b>刻意不加 {@code @CreationTimestamp}</b>，由 Service 用
     * 「截到整秒」的时间显式赋值。
     *
     * <p>原因是 {@code asked_at} 是 {@code DATETIME}（0 位小数精度），
     * 而 MySQL 写入小数秒时是<b>四舍五入</b>而不是截断——{@code .912} 会被存成下一秒。
     * 如果让 Hibernate 自动生成，接口返回的是内存里的 {@code .912}、重新查库却变成下一秒，
     * 同一个字段两次读出来不一样。{@code ClassSessionService} 对
     * {@code started_at} / {@code ended_at} 踩过同一个坑，处理方式保持一致。
     *
     * <p>{@code @CreationTimestamp} 会在 insert 时<b>无条件覆盖</b>手动设的值，
     * 所以这里必须去掉它、改成普通列。
     */
    @Column(name = "asked_at", nullable = false, updatable = false)
    private LocalDateTime askedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
