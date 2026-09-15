package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 学生提问请求。对应 API 接口文档 §6.7。
 *
 * @param sessionId       课堂 ID
 * @param pageId          <b>当前页的页 ID，不是 pageNo</b>：AI 靠它取本页知识点。
 *                        传错会拿到别的页的内容，而且是静默的、不报错
 * @param question        问题正文，1~500 字
 * @param clientRequestId 幂等键，客户端生成的 UUID。可选——
 *                        不带就退化成「无幂等」而不是报错，
 *                        这样直接 curl 调试时也不用先编一个 UUID
 */
public record QaAskRequest(
        @NotNull(message = "缺少课堂 ID")
        Long sessionId,

        @NotNull(message = "缺少页码")
        Long pageId,

        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最长 500 个字符")
        String question,

        @Size(max = 64, message = "幂等键最长 64 个字符")
        String clientRequestId) {
}
