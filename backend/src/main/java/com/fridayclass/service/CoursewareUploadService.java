package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.CoursewareUploadResponse;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.entity.User;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 课件上传（F001）：落盘 → 逐页抽取文字 → 生成网页幻灯片 → 落库。
 *
 * <p>完成后的状态为 {@code CONVERTED}：页面已就绪，但尚未做 AI 知识点解析
 * （那属于 F002，会进一步流转到 {@code PARSING → PARSED}）。
 *
 * <h3>为什么用 TransactionTemplate 而不是 @Transactional</h3>
 * 磁盘 IO 与 POI 解析（CPU + 内存密集型，可能持续数秒到数十秒）**绝不能**包在数据库
 * 事务里：连接池默认只有 10 根连接，几次大课件并发上传就会占满全部连接，
 * 导致登录、列表等所有接口一起阻塞。这里改成：文件与解析在事务外，
 * 事务只包住纯数据库写入的两段。
 */
@Service
public class CoursewareUploadService {

    private static final Logger log = LoggerFactory.getLogger(CoursewareUploadService.class);

    /** courseware.name 列长度上限。 */
    private static final int MAX_NAME_LENGTH = 200;

    private final FileStorageService fileStorageService;
    private final PptxConverter pptxConverter;
    private final CoursewareRepository coursewareRepository;
    private final CoursewarePageRepository coursewarePageRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public CoursewareUploadService(FileStorageService fileStorageService,
                                   PptxConverter pptxConverter,
                                   CoursewareRepository coursewareRepository,
                                   CoursewarePageRepository coursewarePageRepository,
                                   UserRepository userRepository,
                                   PlatformTransactionManager transactionManager) {
        this.fileStorageService = fileStorageService;
        this.pptxConverter = pptxConverter;
        this.coursewareRepository = coursewareRepository;
        this.coursewarePageRepository = coursewarePageRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CoursewareUploadResponse upload(MultipartFile file, UserPrincipal principal) {
        User uploader = loadUploader(principal);

        // —— 以下两步在事务外：磁盘 IO 与 CPU 解析都不该占着数据库连接 ——
        String storedPath = fileStorageService.storeCoursewareFile(file);

        // 记录本次写入的文件，任何后续失败都要清掉，避免留下孤儿文件
        List<String> writtenPaths = new ArrayList<>();
        writtenPaths.add(storedPath);

        try {
            List<PptxConverter.Slide> slides =
                    pptxConverter.extractSlides(fileStorageService.resolve(storedPath));

            String displayName = sanitizeName(file.getOriginalFilename());
            return transactionTemplate.execute(status ->
                    persist(uploader, displayName, storedPath, slides, writtenPaths));

        } catch (RuntimeException ex) {
            writtenPaths.forEach(fileStorageService::deleteQuietly);
            throw ex;
        }
    }

    /** 只包数据库写入：课件、课件页。 */
    private CoursewareUploadResponse persist(User uploader,
                                             String displayName,
                                             String storedPath,
                                             List<PptxConverter.Slide> slides,
                                             List<String> writtenPaths) {
        Courseware courseware = new Courseware();
        courseware.setName(displayName);
        courseware.setFilePath(storedPath);
        courseware.setUploader(uploader);
        courseware.setStatus(CoursewareStatus.CONVERTED);
        courseware.setPageCount(slides.size());
        courseware.setUploadedAt(LocalDateTime.now());
        courseware = coursewareRepository.save(courseware);

        // 网页幻灯片按课件 ID 分目录存放
        List<CoursewarePage> pages = new ArrayList<>(slides.size());
        for (PptxConverter.Slide slide : slides) {
            String html = pptxConverter.toSlideHtml(slide.pageNo(), slide.text());
            String slidePath = "slides/" + courseware.getId() + "/page" + slide.pageNo() + ".html";
            fileStorageService.writeText(slidePath, html);
            writtenPaths.add(slidePath);

            CoursewarePage page = new CoursewarePage();
            page.setCourseware(courseware);
            page.setPageNo(slide.pageNo());
            page.setTextContent(slide.text());
            page.setSlideUrl(slidePath);
            pages.add(page);
        }
        coursewarePageRepository.saveAll(pages);

        log.info("courseware_uploaded id={} pages={} uploader={}",
                courseware.getId(), pages.size(), uploader.getUsername());

        return CoursewareUploadResponse.from(courseware);
    }

    private User loadUploader(UserPrincipal principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new BusinessException(401, "登录已失效，请重新登录"));
    }

    /**
     * 只取文件名部分并限长：防止客户端传来的原始名里带路径分隔符或超长内容。
     * 真实落盘名是 UUID，这里只是给人看的展示名。
     */
    private String sanitizeName(String original) {
        if (original == null || original.isBlank()) {
            return "未命名课件";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.strip();
        if (name.isEmpty()) {
            return "未命名课件";
        }
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        // 按码点截断，避免把 emoji 等代理对切成半个字符
        int end = name.offsetByCodePoints(0, Math.min(
                name.codePointCount(0, name.length()), MAX_NAME_LENGTH));
        return name.substring(0, end);
    }
}
