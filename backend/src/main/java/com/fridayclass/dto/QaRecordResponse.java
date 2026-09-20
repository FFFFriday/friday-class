package com.fridayclass.dto;

import com.fridayclass.entity.QaRecord;
import com.fridayclass.enums.QaStatus;

import java.time.LocalDateTime;

/**
 * 问答记录响应。对应 API 接口文档 §6.7（提问）与 §6.8（记录列表）。
 *
 * @param id             问答记录 ID。<b>{@code status = SKIPPED} 时为 {@code null}</b>，
 *                       因为那种情况根本没有落库
 * @param conversationId 所属 AI 会话。<b>老记录为 {@code null} 属正常</b>——
 *                       它们产生于「会话管理」之前。前端按「历史对话」归组展示即可
 * @param pageId         提问时所在页的页 ID。课后提问为 {@code null}
 * @param pageNo         提问时所在页的页码（展示用）
 * @param question       问题原文
 * @param answer         回答正文。{@code FAILED} 时是友好提示文案而不是报错；
 *                       {@code SKIPPED} 时是引导文案（「课件还在解析中」之类）
 * @param status         见 {@link QaStatus}
 * @param turn           本会话里的第几轮（从 1 开始）。老记录为 {@code null}
 * @param askedAt        提问时间；{@code SKIPPED} 时为 {@code null}
 */
public record QaRecordResponse(
        Long id,
        Long conversationId,
        Long pageId,
        Integer pageNo,
        String question,
        String answer,
        String status,
        Integer turn,
        LocalDateTime askedAt) {

    /** 从实体组装。{@code turn} 不在表里，由调用方按需补（见 QaService）。 */
    public static QaRecordResponse from(QaRecord record) {
        return new QaRecordResponse(
                record.getId(),
                record.getConversationId(),
                record.getPage() == null ? null : record.getPage().getId(),
                record.getPage() == null ? null : record.getPage().getPageNo(),
                record.getQuestion(),
                record.getAnswer(),
                record.getStatus() == null ? null : record.getStatus().name(),
                null,
                record.getAskedAt());
    }

    /** 带轮次的版本。 */
    public static QaRecordResponse from(QaRecord record, Integer turn) {
        QaRecordResponse base = from(record);
        return new QaRecordResponse(base.id(), base.conversationId(), base.pageId(), base.pageNo(),
                base.question(), base.answer(), base.status(), turn, base.askedAt());
    }

    /**
     * 构造一个「没调模型、也不落库」的临时提示。
     *
     * <p>三种情况走这里：课件解析中 / 解析失败 / 本页确无内容（设计文档 §3.5）。
     * 前端应把它当<b>临时提示</b>渲染，<b>不要</b>追加进问答列表——
     * 否则刷新页面后它会消失，看起来像「记录丢了」。
     */
    public static QaRecordResponse skipped(Long pageId, Integer pageNo, String question, String message) {
        return new QaRecordResponse(null, null, pageId, pageNo, question, message,
                QaStatus.SKIPPED.name(), null, null);
    }
}
