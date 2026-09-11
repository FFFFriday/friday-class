package com.fridayclass.controller;

import com.fridayclass.common.BusinessException;
import com.fridayclass.service.FileStorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网页幻灯片访问。路径与契约 2.4 中 {@code slideUrl} 的取值一致。
 *
 * <p><b>安全提示</b>：这里返回的 HTML 是由**用户上传的课件**转换来的，属于不可信内容。
 * 虽然转义已经做了，但仍加两道防护，避免日后转义出现回归时在同源下形成存储型 XSS：
 * <ul>
 *   <li>{@code Content-Security-Policy: sandbox} —— 让页面运行在唯一源下，
 *       拿不到本站的 Cookie 与 localStorage；</li>
 *   <li>{@code X-Content-Type-Options: nosniff} —— 禁止浏览器嗅探类型。</li>
 * </ul>
 */
@RestController
public class SlideController {

    private final FileStorageService fileStorageService;

    public SlideController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 取某一页的网页幻灯片。 */
    @GetMapping("/slides/{coursewareId}/page{pageNo}.html")
    public ResponseEntity<String> slide(@PathVariable Long coursewareId, @PathVariable int pageNo) {
        String path = "slides/" + coursewareId + "/page" + pageNo + ".html";
        String html = fileStorageService.readText(path);
        if (html == null) {
            throw new BusinessException(404, "幻灯片不存在");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy",
                        "sandbox; default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }
}
