package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.ParseProgressResponse;
import com.fridayclass.dto.PromptPackResponse;
import com.fridayclass.service.AiParseService;
import com.fridayclass.service.PromptPackService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 相关接口：课件解析（F002）与提示词包。
 *
 * <p>与 {@link CoursewareController} 共用 {@code /api/courseware} 前缀，但路径不重叠：
 * 那边是 {@code /{id}}、{@code /{id}/pages}、{@code /upload}，
 * 这边是 {@code /{id}/parse}、{@code /{id}/parse-progress}、{@code /{id}/prompt-pack}。
 *
 * <h3>权限</h3>
 * <ul>
 *   <li>{@code POST /{id}/parse} —— <b>仅教师</b>。它会让服务端真的调用付费模型，
 *       所以 Controller 和 {@code SecurityConfig} 两处都卡了角色；</li>
 *   <li>另外两个 GET —— 需登录即可。它们<b>故意不在</b> {@code SecurityConfig} 的
 *       匿名白名单里（白名单只放行了课件列表 / 详情 / 页列表三个公开路径），
 *       因为它们含知识点内容，属于课堂内部资料。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/courseware")
public class CoursewareAiController {

    private final AiParseService aiParseService;
    private final PromptPackService promptPackService;

    public CoursewareAiController(AiParseService aiParseService,
                                  PromptPackService promptPackService) {
        this.aiParseService = aiParseService;
        this.promptPackService = promptPackService;
    }

    /**
     * 触发 AI 解析（教师手动点「AI 解析」按钮）。
     *
     * <p><b>立即返回</b>当前进度，真正的逐页解析在后台跑——一份 103 页的课件要调 103 次模型，
     * 同步做的话这个请求要挂好几分钟。前端拿到响应后按 {@code progress} 轮询
     * {@code parse-progress} 即可。
     *
     * <p>重复点击不会重复解析：同一份课件已有未结束的任务时返回错误提示。
     */
    @PostMapping("/{id}/parse")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ApiResponse<ParseProgressResponse> parse(@PathVariable Long id) {
        return ApiResponse.ok(aiParseService.trigger(id));
    }

    /**
     * 查询解析进度。从未解析过的课件也能调，返回 {@code status = null} 的空进度。
     *
     * <p>轮询建议：解析中每 2~3 秒一次；{@code status} 变为
     * {@code SUCCESS}/{@code FAILED}/{@code PARTIAL} 后停止轮询。
     */
    @GetMapping("/{id}/parse-progress")
    public ApiResponse<ParseProgressResponse> parseProgress(@PathVariable Long id) {
        return ApiResponse.ok(aiParseService.progress(id));
    }

    /**
     * 提示词包：学生进课堂时一次拉全所有页的知识点与预置提问。
     *
     * <p>返回的 {@code parseVersion} 必须由客户端缓存：解析完成后它会变大，
     * 客户端据此丢弃旧缓存重拉。
     */
    @GetMapping("/{id}/prompt-pack")
    public ApiResponse<PromptPackResponse> promptPack(@PathVariable Long id) {
        return ApiResponse.ok(promptPackService.build(id));
    }
}
