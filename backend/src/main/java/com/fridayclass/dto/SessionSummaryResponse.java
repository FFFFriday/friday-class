package com.fridayclass.dto;

import com.fridayclass.entity.CourseSummary;
import com.fridayclass.enums.SummaryStatus;

import java.time.LocalDateTime;

/**
 * 课堂总结（M5）。
 *
 * <p>生成是<b>异步</b>的（一次要几十秒），所以前端拿到 {@code PENDING} / {@code RUNNING}
 * 时要轮询，拿到 {@code SUCCESS} 才展示正文、拿到 {@code FAILED} 才展示 {@code errorMessage}。
 *
 * @param content 正文。仅 {@code SUCCESS} 时有值
 *
 * <p>「记录过长被截断」这件事<b>直接写在正文开头</b>，不单开字段：
 * {@code course_summary} 没有存这个标记的列，而它又是必须让老师知道的
 * （否则他会以为总结覆盖了全部记录）。写进正文最直接，也不会因为刷新而丢失。
 */
public record SessionSummaryResponse(
        String status,
        String content,
        String errorMessage,
        LocalDateTime generatedAt) {

    /** 还没生成过。前端据此显示「生成总结」按钮而不是空面板。 */
    public static SessionSummaryResponse none() {
        return new SessionSummaryResponse(null, null, null, null);
    }

    public static SessionSummaryResponse of(CourseSummary summary) {
        return new SessionSummaryResponse(
                summary.getStatus() == null ? null : summary.getStatus().name(),
                summary.getContent(),
                summary.getErrorMessage(),
                summary.getGeneratedAt());
    }

    /** 刚提交、还没开始跑。 */
    public static SessionSummaryResponse pending(SummaryStatus status) {
        return new SessionSummaryResponse(status.name(), null, null, null);
    }
}
