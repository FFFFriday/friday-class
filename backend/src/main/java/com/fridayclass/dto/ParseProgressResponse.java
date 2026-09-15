package com.fridayclass.dto;

import com.fridayclass.entity.AiParseTask;

import java.time.LocalDateTime;

/**
 * AI 解析进度。对应 API 接口文档 §6.3。
 *
 * <p>{@code POST /api/courseware/{id}/parse} 与 {@code GET /api/courseware/{id}/parse-progress}
 * 返回同一个形状：触发时直接返回当前进度（通常是 0%），前端不必再补一次 GET。
 *
 * @param coursewareId     课件 ID
 * @param coursewareStatus 课件状态 UPLOADED/CONVERTING/CONVERTED/PARSING/PARSED/FAILED
 * @param taskId           最近一次解析任务 ID；<b>从未解析过则为 null</b>
 * @param status           任务状态 PENDING/RUNNING/SUCCESS/FAILED/PARTIAL；从未解析过为 null
 * @param currentPage      <b>已完成页数</b>（不是「正在处理第几页」）。
 *                         因为页是并发解析的，「当前页」没有意义；已完成数才是前端口径正确的进度。
 * @param totalPages       总页数；未知为 null
 * @param progress         百分比 0~100，前端可直接画进度条
 * @param errorMessage     失败原因（FAILED / PARTIAL 时有值）
 * @param startedAt        开始时间
 * @param finishedAt       结束时间
 */
public record ParseProgressResponse(
        Long coursewareId,
        String coursewareStatus,
        Long taskId,
        String status,
        int currentPage,
        Integer totalPages,
        int progress,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    /** 从未解析过（没有任务行）时的响应。 */
    public static ParseProgressResponse neverParsed(Long coursewareId, String coursewareStatus,
                                                    Integer pageCount) {
        return new ParseProgressResponse(coursewareId, coursewareStatus, null, null,
                0, pageCount, 0, null, null, null);
    }

    public static ParseProgressResponse from(Long coursewareId, String coursewareStatus,
                                             AiParseTask task, Integer coursewarePageCount) {
        if (task == null) {
            return neverParsed(coursewareId, coursewareStatus, coursewarePageCount);
        }
        Integer total = task.getTotalPages() != null ? task.getTotalPages() : coursewarePageCount;
        int done = task.getCurrentPage() == null ? 0 : task.getCurrentPage();
        return new ParseProgressResponse(
                coursewareId,
                coursewareStatus,
                task.getId(),
                task.getStatus() == null ? null : task.getStatus().name(),
                done,
                total,
                percent(done, total),
                task.getErrorMessage(),
                task.getStartedAt(),
                task.getFinishedAt());
    }

    private static int percent(int done, Integer total) {
        if (total == null || total <= 0) {
            return 0;
        }
        return Math.min(100, Math.max(0, done * 100 / total));
    }
}
