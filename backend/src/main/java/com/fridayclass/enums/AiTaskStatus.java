package com.fridayclass.enums;

/**
 * AI 解析任务状态。对应 ai_parse_task.status 字段。
 * 流转：PENDING → RUNNING → SUCCESS / FAILED / PARTIAL。
 */
public enum AiTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    PARTIAL
}
