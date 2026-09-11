package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 课件文件存储。安全要点：
 * <ul>
 *   <li><b>只允许 .pptx</b>：按扩展名白名单校验，并检查文件头魔数（pptx 是 zip，以 PK 开头），
 *       防止改名绕过；</li>
 *   <li><b>不用客户端文件名</b>：落盘名一律用 UUID 生成，从根本上杜绝路径穿越（../）
 *       与同名覆盖；</li>
 *   <li><b>大小二次校验</b>：除了 Spring 的 multipart 限制，这里再查一次，防止配置被改漏；</li>
 *   <li><b>落盘后再校验路径</b>：确认最终路径仍位于存储根目录之下（纵深防御）。</li>
 * </ul>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pptx");
    /** 与 application.yml 的 multipart 限制保持一致。 */
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    /** pptx 本质是 zip，文件头为 "PK"。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    static {
        // 防 zip bomb 的参数集中在 PptxZipSecurity（幂等）。这里必须先调用：
        // 校验发生在解析之前，只在 PptxConverter 里设置的话，这一步就是无防护地开压缩包。
        PptxZipSecurity.apply();
    }

    private final Path root;

    public FileStorageService(@Value("${app.storage.root:./storage}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        log.info("storage_root={}", this.root);
    }

    /**
     * 保存上传的 .pptx。
     *
     * @return 相对存储根目录的路径（用 / 分隔），供写入 courseware.file_path
     */
    public String storeCoursewareFile(MultipartFile file) {
        validate(file);

        String relativeDir = "courseware/" + YearMonth.now().format(MONTH_FORMAT);
        String filename = UUID.randomUUID().toString().replace("-", "") + ".pptx";
        Path targetDir = root.resolve(relativeDir).normalize();
        Path target = targetDir.resolve(filename).normalize();

        // 纵深防御：确保最终路径仍在根目录之内
        if (!target.startsWith(root)) {
            throw new BusinessException("存储路径不合法");
        }

        try {
            Files.createDirectories(targetDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("store_courseware_failed filename={}", filename, ex);
            throw new BusinessException(500, "文件保存失败，请稍后重试");
        }

        return relativeDir + "/" + filename;
    }

    /** 把相对路径解析成绝对路径，并确认没有越出根目录。 */
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("存储路径不合法");
        }
        return target;
    }

    /** 写出一个文本文件（用于网页幻灯片 HTML），返回相对路径。 */
    public String writeText(String relativePath, String content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException ex) {
            log.error("write_text_failed path={}", relativePath, ex);
            throw new BusinessException(500, "文件写入失败");
        }
        return relativePath;
    }

    /** 读取文本文件；不存在返回 null。 */
    public String readText(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.exists(target) ? Files.readString(target) : null;
        } catch (IOException ex) {
            log.error("read_text_failed path={}", relativePath, ex);
            return null;
        }
    }

    /**
     * 尽力删除已写入的文件。用于上传失败后的清理，避免留下孤儿文件。
     * 失败只记日志，不抛异常——清理本身不应掩盖原始错误。
     */
    public void deleteQuietly(String relativePath) {
        if (relativePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception ex) {
            log.warn("cleanup_failed path={} reason={}", relativePath, ex.getMessage());
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("文件过大，单个课件不能超过 " + (MAX_BYTES / 1024 / 1024) + "MB");
        }

        String original = file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("只支持 .pptx 格式的课件");
        }

        if (!isPptx(file)) {
            throw new BusinessException("文件内容不是有效的 .pptx（可能是改了扩展名的其他文件）");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断文件是否真的是 .pptx。
     *
     * <p>只查 "PK" 两个字节是不够的——.docx / .xlsx / .zip / .jar 都是 zip，全都会通过。
     * 所以第二步要真正验证它是一个 PowerPoint 包，这里交给 POI 的 {@link XMLSlideShow}：
     * 它走 zip 的**中央目录**，且构造函数本身就只接受 pptx 包，.docx 会被它拒掉。
     *
     * <p><b>为什么不再自己扫 [Content_Types].xml</b>：原来的实现用 {@code ZipInputStream}
     * 逐个 getNextEntry() 找这个条目，而 {@code ZipInputStream} 是**按条目在流里的物理顺序**
     * 读的，OOXML 规范并不保证 [Content_Types].xml 排在前面。实测一份完全正常的
     * 355 条目课件，它排在第 354 位（最后）——配合当时「只看前 200 个条目」的上限，
     * 就被误判成了「不是有效的 .pptx」。POI 与条目顺序无关，从根上消除这个问题。
     */
    private boolean isPptx(MultipartFile file) {
        // 1) 先做便宜的快速拒绝：pptx 是 zip，文件头必须是 "PK"。
        //    注意要从**原始流**读——ZipInputStream 在 getNextEntry() 之前读的是解压后的数据。
        //    用 readNBytes 而不是 read，后者可能只返回 1 字节导致误判。
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(2);
            if (head.length < 2 || head[0] != ZIP_MAGIC[0] || head[1] != ZIP_MAGIC[1]) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }

        // 2) 再让 POI 做权威判断（MultipartFile 支持重复取流：底层是临时文件或内存字节）
        try (InputStream in = file.getInputStream();
             XMLSlideShow ignored = new XMLSlideShow(in)) {
            return true;
        } catch (Exception ex) {
            // 这里吞掉异常是刻意的：调用方只需知道「不是有效 pptx」。
            // 但仍然记一条 debug 日志，方便排查「为什么我的课件被拒了」。
            log.debug("not_a_pptx reason={}", ex.toString());
            return false;
        }
    }
}
