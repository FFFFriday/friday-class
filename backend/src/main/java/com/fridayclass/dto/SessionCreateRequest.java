package com.fridayclass.dto;

import com.fridayclass.enums.SessionVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建课堂请求。对应 {@code POST /api/session}。
 *
 * <h3>授课对象必须显式三选一（「班级 + 个别学生」可以叠加）</h3>
 *
 * <ul>
 *   <li>① {@code classGroupIds} —— 选班级（**可多选，两个以上就是合班上课**）；</li>
 *   <li>② {@code studentIds} —— 单独勾选个别同学，可以和 ① 叠加；</li>
 *   <li>③ {@code visibility = PUBLIC} —— 公开课。它与 ①② <b>互斥</b>。</li>
 * </ul>
 *
 * <p><b>没有默认值。</b> 这是刻意的：缺省若是「公开」，老师某次忘了选，
 * 结果是这节课对所有学生开放；缺省若是「限定」，忘了选就开出一节谁也看不到的课。
 * 两种静默失败都不该被允许，所以干脆要求必须表态 ——
 * 前端在没选之前禁用提交按钮，而**校验在后端**（{@code ClassSessionService.create}），
 * 前端那层只是体验。
 *
 * <p>{@code visibility} 缺省（{@code null}）按 {@code RESTRICTED} 处理（拒绝优先）。
 */
public record SessionCreateRequest(

        @NotNull(message = "课件不能为空")
        Long coursewareId,

        /** 缺省时用课件名，见 {@code ClassSessionService.resolveTitle}。 */
        @Size(max = 200, message = "课堂名称最长 200 个字符")
        String title,

        /**
         * 授课班级。多选即合班。
         *
         * <p>这些班**必须都是这位教师自己的** —— 否则传别人的班级 ID
         * 就能把别人的学生拉进自己的课堂。这是横向越权，由
         * {@code ClassGroupService.resolveAudienceUserIds} 挡下。
         */
        @Size(max = 50, message = "一次最多选择 50 个班级")
        List<Long> classGroupIds,

        /** 单独勾选的同学（与班级叠加，最终取并集去重）。 */
        @Size(max = 500, message = "一次最多选择 500 名学生")
        List<Long> studentIds,

        /** 公开课标记。与上面两项互斥。 */
        SessionVisibility visibility
) {
}
