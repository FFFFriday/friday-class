package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.Courseware;
import com.fridayclass.repository.CoursewareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 存储占用统计与孤立文件扫描（M6）。
 *
 * <h3>⚠️ 铁律：孤立文件<b>只列不删</b>，清理必须经过确认</h3>
 * 「扫描到磁盘上有文件没人引用 → 自动删掉」听起来很省事，但它是**不可逆**的。
 * 判断逻辑一旦有偏差（比如某次数据库恢复让 courseware 表短暂为空），
 * 自动清理会把整个存储目录扫空，而且没有任何回退手段。
 *
 * <p>所以这里分成两步：{@link #scanOrphans()} 只返回列表，人工看过之后
 * 把要删的 path 传回 {@link #clean(List)}。而且 clean 时会<b>重新校验一次</b>——
 * 从「列出」到「确认删除」之间磁盘可能已经变了，拿旧列表直接删是危险的。
 */
@Service
public class StorageScanService {

    private static final Logger log = LoggerFactory.getLogger(StorageScanService.class);

    /** 孤立文件列表上限。存储出大问题时可能扫出上万条，一次全返回会把响应撑爆。 */
    private static final int MAX_ORPHANS = 500;

    private static final String SLIDES_PREFIX = "slides";

    private final CoursewareRepository coursewareRepository;
    private final FileStorageService storageService;

    public StorageScanService(CoursewareRepository coursewareRepository,
                              FileStorageService storageService) {
        this.coursewareRepository = coursewareRepository;
        this.storageService = storageService;
    }

    /** 占用统计：课件数、幻灯片目录数、各自字节数。 */
    @Transactional(readOnly = true)
    public StorageOverview overview() {
        List<Courseware> coursewares = coursewareRepository.findAll();
        Set<String> referencedFiles = referencedFilePaths(coursewares);
        Set<String> referencedSlideDirs = referencedSlideDirs(coursewares);

        Path root = storageService.root();

        long coursewareBytes = 0L;
        long coursewareFiles = 0L;
        Path coursewareDir = root.resolve("courseware");
        if (Files.isDirectory(coursewareDir)) {
            try (Stream<Path> walk = Files.walk(coursewareDir)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                coursewareFiles = files.size();
                for (Path file : files) {
                    coursewareBytes += sizeOf(file);
                }
            } catch (IOException ex) {
                log.warn("storage_scan_courseware_failed reason={}", ex.getMessage());
            }
        }

        long slidesBytes = 0L;
        long slidesFiles = 0L;
        Path slidesDir = root.resolve(SLIDES_PREFIX);
        if (Files.isDirectory(slidesDir)) {
            try (Stream<Path> walk = Files.walk(slidesDir)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                slidesFiles = files.size();
                for (Path file : files) {
                    slidesBytes += sizeOf(file);
                }
            } catch (IOException ex) {
                log.warn("storage_scan_slides_failed reason={}", ex.getMessage());
            }
        }

        return new StorageOverview(
                coursewares.size(),
                referencedSlideDirs.size(),
                coursewareFiles,
                slidesFiles,
                coursewareBytes,
                slidesBytes,
                coursewareBytes + slidesBytes);
    }

    /**
     * 扫出孤立文件。<b>只列不删。</b>
     *
     * <p>孤立 = 磁盘上有、但没有任何<b>未删除</b>的课件引用它。
     * （已删除的课件在删除时就把文件一起清了，所以它们的文件本来就不该还在；
     * 如果还在，那正是上次清理失败留下的垃圾，应该被扫出来。）
     */
    @Transactional(readOnly = true)
    public List<Orphan> scanOrphans() {
        List<Courseware> coursewares = coursewareRepository.findAll();
        Set<String> referencedFiles = referencedFilePaths(coursewares);
        Set<String> referencedSlideDirs = referencedSlideDirs(coursewares);

        List<Orphan> orphans = new ArrayList<>();
        Path root = storageService.root();

        // ① 源文件目录：扫描 storage/courseware/**
        Path coursewareDir = root.resolve("courseware");
        if (Files.isDirectory(coursewareDir) && orphans.size() < MAX_ORPHANS) {
            try (Stream<Path> walk = Files.walk(coursewareDir)) {
                walk.filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(StorageScanService::toRelative)
                        .filter(relative -> !referencedFiles.contains(relative))
                        .sorted()
                        .limit(MAX_ORPHANS)
                        .forEach(relative -> orphans.add(new Orphan(
                                relative, sizeOf(root.resolve(relative)), "FILE",
                                "没有任何课件引用这个文件")));
            } catch (IOException ex) {
                log.warn("orphan_scan_courseware_failed reason={}", ex.getMessage());
            }
        }

        // ② 幻灯片目录：storage/slides/{coursewareId}/，目录名不是课件 ID 的就是孤儿
        Path slidesDir = root.resolve(SLIDES_PREFIX);
        if (Files.isDirectory(slidesDir) && orphans.size() < MAX_ORPHANS) {
            try (Stream<Path> list = Files.list(slidesDir)) {
                List<Path> dirs = list.filter(Files::isDirectory).toList();
                for (Path dir : dirs) {
                    if (orphans.size() >= MAX_ORPHANS) break;
                    String relative = toRelative(root.relativize(dir));
                    if (!referencedSlideDirs.contains(relative)) {
                        orphans.add(new Orphan(relative, directorySize(dir), "DIR",
                                "对应的课件已不存在"));
                    }
                }
            } catch (IOException ex) {
                log.warn("orphan_scan_slides_failed reason={}", ex.getMessage());
            }
        }

        return orphans;
    }

    /**
     * 清理指定的孤立文件。
     *
     * <p><b>会重新校验一遍</b>：从「列出」到「确认删除」之间，磁盘或数据库都可能变了。
     * 直接照着传进来的清单删，等于把一个可能过期的判断当成了事实。
     * 已经不再是孤儿的（比如刚被某个课件重新引用）会被跳过并如实报回来。
     */
    @Transactional(readOnly = true)
    public CleanReport clean(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new BusinessException("没有指定要清理的路径");
        }

        Set<String> stillOrphan = new HashSet<>();
        for (Orphan orphan : scanOrphans()) {
            stillOrphan.add(orphan.path());
        }

        int removed = 0;
        List<String> skipped = new ArrayList<>();

        for (String raw : paths) {
            String relative = toRelative(Path.of(raw == null ? "" : raw));
            if (!stillOrphan.contains(relative)) {
                // 不在孤立清单里 → 要么已被引用，要么路径非法。两种情况都不该删。
                skipped.add(relative);
                continue;
            }
            if (deleteRecursively(relative)) {
                removed++;
            } else {
                skipped.add(relative);
            }
        }

        log.info("storage_orphan_clean requested={} removed={} skipped={}",
                paths.size(), removed, skipped.size());
        return new CleanReport(paths.size(), removed, skipped);
    }

    // ── 内部 ───────────────────────────────────────────────────

    /** 所有未删除课件的源文件相对路径。 */
    private Set<String> referencedFilePaths(List<Courseware> coursewares) {
        Set<String> paths = new HashSet<>();
        for (Courseware courseware : coursewares) {
            if (courseware.getFilePath() != null && !courseware.getFilePath().isBlank()) {
                paths.add(toRelative(Path.of(courseware.getFilePath())));
            }
        }
        return paths;
    }

    /** 所有未删除课件对应的幻灯片目录相对路径。 */
    private Set<String> referencedSlideDirs(List<Courseware> coursewares) {
        Set<String> dirs = new HashSet<>();
        for (Courseware courseware : coursewares) {
            dirs.add(SLIDES_PREFIX + "/" + courseware.getId());
        }
        return dirs;
    }

    private boolean deleteRecursively(String relative) {
        Path target;
        try {
            target = storageService.resolve(relative);
        } catch (BusinessException ex) {
            // 越出存储根目录的路径一律拒绝（纵深防御）
            log.warn("orphan_clean_rejected_path path={}", relative);
            return false;
        }
        if (!Files.exists(target)) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(target)) {
            // 倒序删除：Files.walk 是先父后子，正序删会先删目录再删里面的文件而报错
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("orphan_delete_failed path={} reason={}", path, ex.getMessage());
                }
            });
            return !Files.exists(target);
        } catch (IOException ex) {
            log.warn("orphan_delete_walk_failed path={} reason={}", relative, ex.getMessage());
            return false;
        }
    }

    /** 统一成「用 / 分隔」的相对路径，避免 Windows 的反斜杠导致比对全部失败。 */
    private static String toRelative(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private long directorySize(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(StorageScanService::sizeOf).sum();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * @param coursewareCount 课件数（未删除的）
     * @param slidesDirCount  幻灯片目录数
     */
    public record StorageOverview(
            int coursewareCount,
            int slidesDirCount,
            long coursewareFileCount,
            long slidesFileCount,
            long coursewareBytes,
            long slidesBytes,
            long totalBytes) {
    }

    /**
     * @param kind FILE（单个文件）或 DIR（整个目录）
     */
    public record Orphan(String path, long sizeBytes, String kind, String reason) {
    }

    /**
     * @param skipped 被跳过的路径：请求时是孤儿、真正删的时候已经不是了
     */
    public record CleanReport(int requested, int removed, List<String> skipped) {
    }
}
