package com.fridayclass.controller;

import com.fridayclass.common.BusinessException;
import com.fridayclass.service.FileStorageService;
import com.fridayclass.service.SlideImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

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
    private final SlideImageService slideImageService;

    public SlideController(FileStorageService fileStorageService,
                           SlideImageService slideImageService) {
        this.fileStorageService = fileStorageService;
        this.slideImageService = slideImageService;
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

    /**
     * 取某一页的**幻灯片图片**（真正渲染出来的 PPT 画面）。
     *
     * <p>与上面那份纯文字 HTML 的区别：HTML 是 F001 用 POI 抽出的文字拼出来的，
     * 只能给 AI 当语料；这个 PNG 是 {@code XSLFSlide.draw()} 画出来的整页画面，
     * 图片、配色、形状全都在，老师和学生看到的就是课件本身。
     *
     * <p>首次请求会现渲染（实测约 270ms），之后永久复用同一份文件。
     *
     * <p><b>为什么可以长缓存</b>：图片内容由 {@code coursewareId + pageNo} 唯一确定，
     * 而课件一旦上传就不再改动（重新上传会生成新的 coursewareId）。
     * 所以让浏览器放心缓存一天，翻页来回切时不会重复下载。
     *
     * <p>这里**不需要**上面那套 sandbox CSP：CSP 是给「可能含脚本的 HTML」用的，
     * PNG 是纯位图，无法执行任何东西；而且本端点是被 {@code <img>} 直接引用的，
     * 加上 CSP 也没有意义。
     */
    @GetMapping("/slides/{coursewareId}/page{pageNo}.png")
    public ResponseEntity<Resource> slideImage(@PathVariable Long coursewareId,
                                               @PathVariable int pageNo) {
        Path png = slideImageService.ensureImage(coursewareId, pageNo);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(png));
    }
}
