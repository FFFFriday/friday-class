package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 学生提问请求。对应 API 接口文档 §6.7 与 §6.12。
 *
 * <p><b>三个 ID 全部可空</b>——这是「课后也能用 AI」的前提：
 * 学生不在任何课堂上时，既没有 {@code sessionId} 也没有 {@code pageId}。
 * 早先它们是 {@code @NotNull}，课后提问会在参数校验这一步就被挡掉，
 * 根本走不到 Service。
 *
 * @param sessionId       课堂 ID。<b>可空</b>（空 = 课后提问，不挂在任何课堂下）
 * @param pageId          <b>当前页的页 ID，不是 pageNo</b>：AI 靠它取本页知识点。
 *                        传错会拿到别的页的内容，而且是静默的、不报错。
 *                        <b>可空</b>（空 = 课后提问，不基于任何一页）
 * @param conversationId  所属 AI 会话。<b>可空</b>——
 *                        传了就用它（会校验归属）；没传就落到该学生在该课件下的
 *                        「默认会话」，找不到就自动建一个。
 *                        这样<b>老前端不改也能继续跑</b>，新 UI 才传它。
 * @param question        问题正文，1~500 字
 * @param clientRequestId 幂等键，客户端生成的 UUID。可选——
 *                        不带就退化成「无幂等」而不是报错，
 *                        这样直接 curl 调试时也不用先编一个 UUID
 */
public record QaAskRequest(
        Long sessionId,

        Long pageId,

        Long conversationId,

        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最长 500 个字符")
        String question,

        @Size(max = 64, message = "幂等键最长 64 个字符")
        String clientRequestId) {
}
