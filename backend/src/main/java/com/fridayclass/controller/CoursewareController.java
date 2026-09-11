package com.fridayclass.controller;

import com.fridayclass.common.ApiResponse;
import com.fridayclass.dto.CoursewarePageResponse;
import com.fridayclass.dto.CoursewareResponse;
import com.fridayclass.dto.CoursewareUploadResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.service.CoursewareService;
import com.fridayclass.service.CoursewareUploadService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课件接口。实现 docs/api-contract.md 第 2 节。
 *
 * <p>2.1 / 2.3 / 2.4 为公开读取（SecurityConfig 白名单匿名放行）；
 * 2.2 上传需要登录且**仅限教师角色**。
 */
@RestController
@RequestMapping("/api/courseware")
public class CoursewareController {

    private final CoursewareService coursewareService;
    private final CoursewareUploadService coursewareUploadService;

    public CoursewareController(CoursewareService coursewareService,
                                CoursewareUploadService coursewareUploadService) {
        this.coursewareService = coursewareService;
        this.coursewareUploadService = coursewareUploadService;
    }

    /** 课件列表：分页 + 关键字模糊 + 状态筛选。 */
    @GetMapping
    public ApiResponse<PageResult<CoursewareResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(coursewareService.list(page, size, keyword, status));
    }

    /**
     * 上传课件（教师专属）。
     *
     * <p>角色校验放在方法级：白名单之外的所有请求都要求登录，这里再收紧到教师。
     * 学生即便拿到合法令牌，也会被挡在 403。
     */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<CoursewareUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(coursewareUploadService.upload(file, principal));
    }

    /** 课件详情。 */
    @GetMapping("/{id}")
    public ApiResponse<CoursewareResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(coursewareService.detail(id));
    }

    /** 课件页列表（幻灯片预览用）。 */
    @GetMapping("/{id}/pages")
    public ApiResponse<ListResult<CoursewarePageResponse>> pages(@PathVariable Long id) {
        return ApiResponse.ok(ListResult.of(coursewareService.pages(id)));
    }
}
