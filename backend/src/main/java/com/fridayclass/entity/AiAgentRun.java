package com.fridayclass.entity;

import com.fridayclass.enums.AgentRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * AI 智能体一次任务的执行记录。对应 {@code ai_agent_run} 表（V4 迁移）。
 *
 * <h3>这张表存在的最大理由：让成本可见</h3>
 *
 * 对一个成本敏感的项目，「AI 一共跑了多少次、烧了多少 token、哪些跑得特别久」
 * 比省下一两次调用有用得多。没有这张表，token 花在哪全靠猜。
 *
 * <h3>为什么把「进度」也放在这里，而不是只存最终结果</h3>
 *
 * 前端要靠轮询展示「正在查询课堂记录…」这种步骤文字。
 * 任务发起时先落一行 {@code RUNNING}，跑的过程中更新 {@code steps}，
 * 结束时改成终态。这样<b>后端重启后前端也不会看到一条永远转圈的任务</b>——
 * 记录在库里，能查到它到底是什么状态。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_agent_run")
public class AiAgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 老师那句话（或快捷任务展开后的指令）。 */
    @Column(nullable = false, length = 1000)
    private String instruction;

    /** 选了哪些课件 / 课堂 / 文件夹 / 格式。JSON 串，只用于回溯，不参与逻辑。 */
    @Column(name = "context_json", length = 500)
    private String contextJson;

    /** 实际用掉了几轮工具调用。 */
    @Column(nullable = false)
    private Integer steps = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentRunStatus status = AgentRunStatus.RUNNING;

    /**
     * 给老师看的答复正文。
     *
     * <p><b>必须落库，不能只留在内存里。</b> 前端靠轮询取结果：
     * 若结果只在内存，服务一重启，那条已经跑完的任务就变成
     * 「状态是 SUCCESS、却一个字都读不出来」，用户看到一片空白，
     * 而且刷新多少次都一样。
     *
     * <p>用 {@code TEXT} 而不是 {@code VARCHAR(n)}：模型给的答复长度不可控，
     * 而截断一段正在读的文字比多占几百字节糟糕得多。
     */
    @Column(columnDefinition = "TEXT")
    private String result;

    /** 失败在第几轮、为什么。成功时为 null。 */
    @Column(length = 1000)
    private String error;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "elapsed_ms", nullable = false)
    private Long elapsedMs = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
