package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.QaAskRequest;
import com.fridayclass.dto.QaRecordResponse;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.QaService;
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

    public QaController(QaService qaService) {
        this.qaService = qaService;
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
                                                            @RequestParam(required = false) Long pageId) {
        List<QaRecordResponse> records = qaService.records(sessionId, pageId);
        return ApiResponse.ok(ListResult.of(records));
    }
}
