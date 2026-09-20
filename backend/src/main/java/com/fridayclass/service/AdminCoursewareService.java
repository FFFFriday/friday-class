package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.admin.AdminCoursewareResponse;
import com.fridayclass.entity.Courseware;
import com.fridayclass.repository.CoursewareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 管理端的课件操作（M6）：列表、删除、重新解析。
 *
 * <h3>删除：数据库行 + 源文件 + 幻灯片目录，<b>三处一起清</b></h3>
 * 「删课件」很容易只做一半：删了库里的行，磁盘上的 .pptx 和几十张幻灯片图片还留着。
 * 单份课件 25MB 起，删十份就是几百兆垃圾，而且<b>没有任何界面能看出来</b>——
 * 只有等磁盘满。三处清理收口在一处，就不会有人只做一半。
 *
 * <h3>为什么数据库行是软删除</h3>
 * 这张表被 {@code class_session} 用外键指着（{@code ON DELETE RESTRICT}），
 * 只要有过课堂就物理删不掉；就算能删，也会 CASCADE 掉
 * {@code courseware_page} → {@code knowledge_point} / {@code preset_question}，
 * 而那些是课堂记录的上下文。软删除后它不再出现在任何列表里，效果一样，风险小得多。
 *
 * <h3>顺序：先提交事务，再删文件</h3>
 * 反过来的话，事务一旦回滚，库里的行还在、文件却没了——那个课件就永远打不开了。
 * 现在这个顺序最坏是「行已删、文件残留」，那正好是孤立文件清理能兜住的。
 */
@Service
public class AdminCoursewareService {

    private static final Logger log = LoggerFactory.getLogger(AdminCoursewareService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final CoursewareRepository coursewareRepository;
    private final FileStorageService storageService;
    private final AiParseService aiParseService;
    private final TransactionTemplate transactionTemplate;

    public AdminCoursewareService(CoursewareRepository coursewareRepository,
                                  FileStorageService storageService,
                                  AiParseService aiParseService,
                                  PlatformTransactionManager transactionManager) {
        this.coursewareRepository = coursewareRepository;
        this.storageService = storageService;
        this.aiParseService = aiParseService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 列表。返回的是**快照**而不是实体。
     *
     * <p>为什么要多一层快照：这个接口要做两件性质完全不同的事——
     * 读数据库（含懒加载的 uploader）与读磁盘（算文件大小）。
     * 直接返回实体会逼着调用方在事务外访问 {@code getUploader()}，
     * 那就是 {@code LazyInitializationException}（本项目已经踩过两次）。
     * 快照在事务内把需要的字段一次性取出来，之后随便在哪用都不会再碰数据库。
     *
     * <p>磁盘 I/O 刻意留在事务外：它是慢操作，不该占着数据库连接。
     */
    @Transactional(readOnly = true)
    public Page<CoursewareSnapshot> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return coursewareRepository.findAll(PageRequest.of(Math.max(page, 1) - 1, safeSize))
                .map(AdminCoursewareService::snapshot);
    }

    /**
     * 快照 → 响应。会去磁盘查占用，所以**必须在事务外调用**。
     *
     * <p>大小是实时查的，不在数据库里存一份：存了就要维护一致性，
     * 而它只在管理端这一处用到，实时查最省心。
     */
    public AdminCoursewareResponse toResponse(CoursewareSnapshot snapshot) {
        long sourceBytes = sizeOfFile(snapshot.filePath());
        long slidesBytes = directorySize("slides/" + snapshot.id());
        return new AdminCoursewareResponse(
                snapshot.id(), snapshot.name(), snapshot.status(), snapshot.pageCount(),
                snapshot.uploaderId(), snapshot.uploaderName(), snapshot.uploadedAt(),
                sourceBytes, slidesBytes);
    }

    /** 把实体拍成快照。必须在事务内调用（会触发 uploader 的懒加载）。 */
    private static CoursewareSnapshot snapshot(Courseware courseware) {
        var uploader = courseware.getUploader();
        return new CoursewareSnapshot(
                courseware.getId(),
                courseware.getName(),
                courseware.getStatus() == null ? null : courseware.getStatus().name(),
                courseware.getPageCount(),
                uploader == null ? null : uploader.getId(),
                uploader == null ? null : uploader.getUsername(),
                courseware.getUploadedAt(),
                courseware.getFilePath());
    }

    /**
     * 课件快照。字段就是列表要显示的那些，多一个 {@code filePath} 供事务外算大小。
     */
    public record CoursewareSnapshot(
            Long id,
            String name,
            String status,
            Integer pageCount,
            Long uploaderId,
            String uploaderName,
            java.time.LocalDateTime uploadedAt,
            String filePath) {
    }

    /**
     * 删除一份课件。
     *
     * @return 实际清掉了什么（供审计与界面提示）
     */
    public DeleteReport delete(Long coursewareId) {
        // 1) 短事务：标记删除，并取出两个文件路径
        Paths paths = transactionTemplate.execute(status -> {
            Courseware courseware = coursewareRepository.findById(coursewareId)
                    .orElseThrow(() -> new BusinessException(404, "课件不存在"));

            courseware.setDeleted(true);
            coursewareRepository.save(courseware);

            return new Paths(courseware.getName(), courseware.getFilePath(),
                    "slides/" + coursewareId);
        });

        // 2) 事务提交后再动磁盘
        boolean sourceRemoved = deleteFileQuietly(paths.filePath());
        long slidesRemoved = deleteDirectoryQuietly(paths.slidesDir());

        log.info("courseware_deleted id={} name={} sourceRemoved={} slidesRemoved={}",
                coursewareId, paths.name(), sourceRemoved, slidesRemoved);

        return new DeleteReport(coursewareId, paths.name(), sourceRemoved, slidesRemoved);
    }

    /**
     * 重新解析：删掉旧的知识点与预置提问，再跑一次解析。
     *
     * <p><b>会真调付费模型</b>（一份百页课件约 0.8 元），所以前端必须二次确认。
     * 权限由 {@code /api/admin/**} 拦死——这里不再判角色。
     */
    public void reparse(Long coursewareId) {
        Courseware courseware = coursewareRepository.findById(coursewareId)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));
        if (courseware.getStatus() == null) {
            throw new BusinessException("课件状态异常，无法解析");
        }
        // 交给既有的解析服务：它自己会处理「正在解析中」「页数」等边界
        aiParseService.trigger(coursewareId);
        log.info("courseware_reparse_triggered id={}", coursewareId);
    }

    // ── 磁盘操作 ───────────────────────────────────────────────

    private long sizeOfFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = storageService.resolve(relativePath);
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private long directorySize(String relativeDir) {
        try {
            Path dir = storageService.resolve(relativeDir);
            if (!Files.isDirectory(dir)) {
                return 0L;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                return walk.filter(Files::isRegularFile).mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException ex) {
                        return 0L;
                    }
                }).sum();
            }
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** 删单个文件。返回是否真的删掉了一个存在的文件。 */
    private boolean deleteFileQuietly(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(storageService.resolve(relativePath));
        } catch (Exception ex) {
            log.warn("courseware_file_delete_failed path={} reason={}", relativePath, ex.getMessage());
            return false;
        }
    }

    /**
     * 删整个幻灯片目录。返回删掉的文件数。
     *
     * <p>必须<b>倒序</b>删除：{@code Files.walk} 是「先父后子」，
     * 正序会先删目录再删里面的文件，直接抛 {@code DirectoryNotEmptyException}。
     */
    private long deleteDirectoryQuietly(String relativeDir) {
        Path dir;
        try {
            dir = storageService.resolve(relativeDir);
        } catch (BusinessException ex) {
            return 0L;
        }
        if (!Files.isDirectory(dir)) {
            return 0L;
        }

        long[] count = {0L};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                    count[0]++;
                } catch (IOException ex) {
                    log.warn("slides_delete_failed path={} reason={}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("slides_walk_failed dir={} reason={}", relativeDir, ex.getMessage());
            return 0L;
        }
        return count[0];
    }

    private record Paths(String name, String filePath, String slidesDir) {
    }

    /**
     * @param sourceRemoved 源 .pptx 是否真的删掉了（本来就不存在时是 false，属正常）
     * @param slidesRemoved 删掉的幻灯片文件数
     */
    public record DeleteReport(Long coursewareId, String name,
                               boolean sourceRemoved, long slidesRemoved) {
    }
}
