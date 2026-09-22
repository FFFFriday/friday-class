package com.fridayclass.dto.agent;

import com.fridayclass.entity.AiAgentRun;

/**
 * 一次 AI 智能体任务的状态。发起时与轮询时返回同一个形状。
 *
 * <h3>为什么要有 {@code stepText}</h3>
 *
 * 一次任务要跑十几秒到一分钟。前端每 1.5 秒轮询一次，
 * 如果只回一个 {@code RUNNING}，老师看到的是一个不动的转圈——
 * 他会以为卡死，然后<b>反复点重试</b>，同时触发好几个付费任务。
 *
 * <p>{@code stepText} 是「正在查询课堂记录…」这类文字，让等待可解释。
 *
 * @param taskId    任务 ID，前端后续用它轮询
 * @param status    RUNNING / SUCCESS / FAILED / LIMIT
 * @param steps     已经用掉的工具轮数
 * @param stepText  当前步骤的人话描述；非 RUNNING 时为 null
 * @param result    终态时的说明文字（SUCCESS / LIMIT 有，FAILED 时为 null）
 * @param error     失败原因；仅 FAILED 有
 * @param elapsedMs 已耗时（毫秒）
 */
public record AgentTaskResponse(
        Long taskId,
        String status,
        int steps,
        String stepText,
        String result,
        String error,
        long elapsedMs) {

    /** 运行中：从库里读状态与轮数，步骤文字从内存取。 */
    public static AgentTaskResponse running(AiAgentRun run, String stepText, long elapsedMs) {
        return new AgentTaskResponse(run.getId(), run.getStatus().name(),
                nullToZero(run.getSteps()), stepText, null, null, elapsedMs);
    }

    /**
     * 终态。
     *
     * <p>SUCCESS / LIMIT 的说明文字放在 {@code result} 里；
     * FAILED 的放在 {@code error} 里，且 {@code result} 留空 ——
     * 让前端可以按「有没有 result」决定是显示成一次正常答复还是红色报错，
     * 不必去比字符串。
     */
    public static AgentTaskResponse finished(AiAgentRun run) {
        boolean failed = "FAILED".equals(run.getStatus().name());
        return new AgentTaskResponse(
                run.getId(),
                run.getStatus().name(),
                nullToZero(run.getSteps()),
                null,
                failed ? null : run.getResult(),
                failed ? run.getError() : null,
                run.getElapsedMs() == null ? 0L : run.getElapsedMs());
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
