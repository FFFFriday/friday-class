package com.fridayclass.dto;

import com.fridayclass.entity.CoursewarePage;

/**
 * 课件页响应。对应契约 2.4。
 */
public record CoursewarePageResponse(Long id, Integer pageNo, String textContent, String slideUrl) {

    public static CoursewarePageResponse from(CoursewarePage page) {
        return new CoursewarePageResponse(
                page.getId(),
                page.getPageNo(),
                page.getTextContent(),
                toUrl(page.getSlideUrl()));
    }

    /**
     * 库里存的是相对存储根目录的路径（如 {@code slides/7/page1.html}），
     * 对外要输出成可直接访问的 URL（{@code /slides/7/page1.html}），与契约一致。
     */
    private static String toUrl(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        String normalized = storedPath.replace('\\', '/');
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
