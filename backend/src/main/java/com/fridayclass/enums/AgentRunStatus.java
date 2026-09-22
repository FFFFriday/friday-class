package com.fridayclass.enums;

/**
 * AI 智能体一次任务的状态。
 *
 * <p>取值与 {@code ai_agent_run.status} 列一一对应，用 {@code EnumType.STRING} 存字面量。
 */
public enum AgentRunStatus {

    /** 正在跑。落库时先写这个值，跑完再更新 —— 前端轮询读到的就是它。 */
    RUNNING,

    /** 正常结束：模型给出了最终答复。 */
    SUCCESS,

    /** 出错结束：模型调用失败、工具抛异常等。{@code error} 列会说明第几轮、为什么。 */
    FAILED,

    /**
     * 步数用尽（超过 6 轮工具调用）。
     *
     * <p><b>刻意与 {@link #FAILED} 分开。</b> 两者对用户的意义完全不同：
     * FAILED 是「出问题了」，LIMIT 是「它还没干完，但我不让它继续了」。
     * 合并成一个的话，界面只能笼统说「任务失败」，
     * 老师会以为是系统坏了、反复重试，而真正该做的是把要求拆小一点。
     */
    LIMIT
}
