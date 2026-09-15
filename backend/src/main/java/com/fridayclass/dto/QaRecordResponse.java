package com.fridayclass.dto;

import com.fridayclass.entity.QaRecord;
import com.fridayclass.enums.QaStatus;

import java.time.LocalDateTime;

/**
 * 问答记录响应。对应 API 接口文档 §6.7（提问）与 §6.8（记录列表）。
 *
 * @param id       问答记录 ID。<b>{@code status = SKIPPED} 时为 {@code null}</b>，
 *                 因为那种情况根本没有落库
 * @param pageId   提问时所在页的页 ID
 * @param pageNo   提问时所在页的页码（展示用）
 * @param question 问题原文
 * @param answer   回答正文。{@code FAILED} 时是友好提示文案而不是报错；
 *                 {@code SKIPPED} 时是引导文案（「课件还在解析中」之类）
 * @param status   见 {@link QaStatus}
 * @param askedAt  提问时间；{@code SKIPPED} 时为 {@code null}
 */
public record QaRecordResponse(
        Long id,
        Long pageId,
        Integer pageNo,
        String question,
        String answer,
        String status,
        LocalDateTime askedAt) {

    public static QaRecordResponse from(QaRecord record) {
        return new QaRecordResponse(
                record.getId(),
                record.getPage() == null ? null : record.getPage().getId(),
                record.getPage() == null ? null : record.getPage().getPageNo(),
                record.getQuestion(),
                record.getAnswer(),
                record.getStatus() == null ? null : record.getStatus().name(),
                record.getAskedAt());
    }

    /**
     * 构造一个「没调模型、也不落库」的临时提示。
     *
     * <p>三种情况走这里：课件解析中 / 解析失败 / 本页确无内容（设计文档 §3.5）。
     * 前端应把它当<b>临时提示</b>渲染，<b>不要</b>追加进问答列表——
     * 否则刷新页面后它会消失，看起来像「记录丢了」。
     */
    public static QaRecordResponse skipped(Long pageId, Integer pageNo, String question, String message) {
        return new QaRecordResponse(null, pageId, pageNo, question, message,
                QaStatus.SKIPPED.name(), null);
    }
}
