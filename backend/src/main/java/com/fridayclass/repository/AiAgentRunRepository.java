package com.fridayclass.repository;

import com.fridayclass.entity.AiAgentRun;
import com.fridayclass.enums.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 智能体任务记录。
 *
 * <p>与 {@link AiGeneratedFileRepository} 同样的立场：查单个任务必须带 owner，
 * 否则前端只要把 url 里的 id 换一个，就能读到别人任务的完整指令与错误信息。
 */
public interface AiAgentRunRepository extends JpaRepository<AiAgentRun, Long> {

    /** 按 id 取，且必须是<b>我的</b>任务。轮询接口走它。 */
    Optional<AiAgentRun> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * 按状态取任务。只给启动时的僵尸清理用。
     *
     * <p>不带 owner 条件在这里是可以的：调用方是启动流程，
     * 要处理的就是<b>全表</b>的残留 RUNNING 行，没有「谁的」这个概念。
     */
    List<AiAgentRun> findByStatus(AgentRunStatus status);

    /**
     * 某人当前有多少个任务在跑（含排队还没开跑的）。发起前用它做并发闸门。
     *
     * <p>数的是数据库里的 {@code RUNNING} 行 —— 排队中的任务在库里也是 RUNNING，
     * 所以这个数等于「已受理但还没结束」的总量，正是要限的东西。
     */
    long countByOwnerIdAndStatus(Long ownerId, AgentRunStatus status);
}
