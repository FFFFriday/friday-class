package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.QaAskRequest;
import com.fridayclass.dto.QaRecordResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.QaService;
import com.fridayclass.service.SessionAccessService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生问答接口（F004）。对应 API 接口文档 §6.7 / §6.8。
 *
 * <p>权限：需登录即可，<b>不区分角色</b>——老师也可能想在自己的课堂里问一句。
 * 这两个路径不在 {@code SecurityConfig} 的匿名白名单里，所以默认就要登录。
 *
 * <p>⚠️ <b>已知取舍</b>：没有选课 / 成员表，所以任何登录用户都能读任意课堂的问答记录
 * （含别人的提问）。MVP 接受（设计文档 M7），但要清楚这是隐私相关项，上线前要重新评估。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;
    private final SessionAccessService sessionAccessService;

    public QaController(QaService qaService, SessionAccessService sessionAccessService) {
        this.qaService = qaService;
        this.sessionAccessService = sessionAccessService;
    }

    /**
     * 学生提问。
     *
     * <p>返回的 {@code status} 有三种取值，前端必须分别处理：
     * <ul>
     *   <li>{@code SUCCESS} —— 正常回答，追加进列表；</li>
     *   <li>{@code FAILED} —— 模型没答上，{@code answer} 是友好提示。<b>仍然算一次问答</b>，
     *       也仍然在列表里（F006 统计要能看出没答上）；</li>
     *   <li>{@code SKIPPED} —— 根本没调模型（解析中 / 解析失败 / 本页无内容），
     *       {@code id} 为 {@code null}。<b>不要追加进列表</b>，当临时提示显示即可。</li>
     * </ul>
     */
    @PostMapping("/ask")
    public ApiResponse<QaRecordResponse> ask(@Valid @RequestBody QaAskRequest request,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        // sessionId 可空（课后就某一页自由提问）。带了就说明是在某个课堂上下文里问的，
        // 那就必须先确认这个学生在这节课的名单里 —— 否则可以借别人的课堂上下文
        // 拿到那节课的页码与知识点（问题点 7）
        if (request.sessionId() != null) {
            sessionAccessService.requireStudentAccess(request.sessionId(), principal);
        }
        return ApiResponse.ok(qaService.ask(principal.getId(), request));
    }

    /**
     * 问答记录列表（不分页）。
     *
     * @param sessionId 课堂 ID（必填）
     * @param pageId    只看某一页的问答（可选，老师翻回上一页时有用）
     */
    @GetMapping("/records")
    public ApiResponse<ListResult<QaRecordResponse>> records(@RequestParam Long sessionId,
                                                            @RequestParam(required = false) Long pageId,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        sessionAccessService.requireStudentAccess(sessionId, principal);
        List<QaRecordResponse> records = qaService.records(sessionId, pageId);
        return ApiResponse.ok(ListResult.of(records));
    }
}
