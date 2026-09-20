package com.fridayclass.dto;

import java.time.LocalDateTime;

/**
 * 课堂记录概览（M5）。
 *
 * @param durationMinutes 上课时长（分钟）。未下课则算到「此刻」，
 *                        所以一节课还在进行时这个数会随刷新变大，属正常
 * @param participantCount 去过这节课的人数（来自 session_participant，一人一行）
 * @param chatCount        讨论区有效发言数（不含已撤回）
 * @param qaCount          AI 问答总数
 * @param studentQaCount   **聊过 AI 的学生人数**——
 *                         与 qaCount 不同：三个学生各问 2 次是 qaCount=6、studentQaCount=3
 */
public record SessionRecordOverview(
        Long sessionId,
        String title,
        String coursewareName,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long durationMinutes,
        long participantCount,
        long chatCount,
        long qaCount,
        long studentQaCount) {
}
