package com.fridayclass.service.agent;

import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.AiGeneratedFile;
import com.fridayclass.enums.FileFormat;
import com.fridayclass.repository.AiGeneratedFileRepository;
import com.fridayclass.service.AdminAuditService;
import com.fridayclass.service.agent.export.MarkdownExporterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AI 工作区里的文件读写。<b>所有落盘与列目录都要经过这里。</b>
 *
 * <h2>两处 owner 隔离（A1 §4.5）</h2>
 * <ol>
 *   <li><b>路径层</b>：目录按 {@code u{userId}} 分家，由 {@link WorkspacePathSandbox} 保证
 *       拼出来的路径不会跑到别人的根里去；</li>
 *   <li><b>接口层</b>：下载/查询一律走 {@code findByIdAndOwnerId}。
 *       光有目录隔离是不够的 —— 文件 id 是<b>连续自增</b>的，
 *       只按 id 取文件的话，把 url 里的 5 改成 6 就能下载别人的文档。</li>
 * </ol>
 *
 * <p>两处都要做，缺一不可：只有路径层挡不住「猜 id」，只有接口层挡不住「工具写错目录」。
 */
@Service
public class WorkspaceFileService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileService.class);

    /** 列表软上限。老师的产出物是给人看的，不是导数据的。 */
    private static final int MAX_LIST = 200;

    /**
     * 默认文件夹。
     *
     * <p>老师第一次进来时一个文件夹都没有，下拉框是空的、也没法生成。
     * 自动给一个，动线不断；他想改名字随时可以。
     */
    public static final String DEFAULT_FOLDER = "我的资料";

    /** 工具读回文件时的字符上限：一次把整份文档塞回模型上下文会烧掉大量 token。 */
    private static final int MAX_READ_CHARS = 20_000;

    private final WorkspacePathSandbox sandbox;
    private final AiGeneratedFileRepository fileRepository;
    private final MarkdownExporterRegistry exporters;
    private final AdminAuditService auditService;

    public WorkspaceFileService(WorkspacePathSandbox sandbox,
                                AiGeneratedFileRepository fileRepository,
                                MarkdownExporterRegistry exporters,
                                AdminAuditService auditService) {
        this.sandbox = sandbox;
        this.fileRepository = fileRepository;
        this.exporters = exporters;
        this.auditService = auditService;
    }

    // ── 文件夹 ────────────────────────────────────────────────

    /**
     * 我的文件夹列表。
     *
     * <p><b>扫磁盘而不是查数据库</b>：老师可以手动新建一个空文件夹，
     * 那种文件夹在 {@code ai_generated_file} 里一行记录都没有，
     * 查库的话它永远不会出现在下拉框里 —— 建了就「不见了」，很费解。
     *
     * <p>首次调用会创建用户根目录与默认文件夹（幂等）。这是一个 GET 里的写操作，
     * 刻意为之：否则「第一次进来没有文件夹」这个状态得由前端额外处理一遍。
     */
    @Transactional
    public List<String> listFolders(Long userId) {
        Path root = sandbox.resolveUserRoot(userId);
        try {
            if (!Files.isDirectory(root)) {
                Files.createDirectories(root);
                log.info("agent_workspace_created userId={} root={}", userId, root);
                return List.of(DEFAULT_FOLDER);
            }
            List<String> folders = listDirectories(root);
            if (folders.isEmpty()) {
                Files.createDirectories(root.resolve(DEFAULT_FOLDER));
                return List.of(DEFAULT_FOLDER);
            }
            return folders;
        } catch (IOException ex) {
            log.error("agent_list_folders_failed userId={} root={}", userId, root, ex);
            throw new BusinessException(500, "读取文件夹失败，请稍后重试");
        }
    }

    private List<String> listDirectories(Path root) throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            List<String> names = new ArrayList<>();
            children.filter(Files::isDirectory)
                    .forEach(path -> names.add(path.getFileName().toString()));
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    /** 新建文件夹。只接收<b>名字</b>，路径由服务端拼。 */
    @Transactional
    public String createFolder(Long userId, String rawName) {
        String name = WorkspacePathSandbox.requireSafeFolder(rawName);
        Path folder = sandbox.resolveFolder(userId, name);
        try {
            Files.createDirectories(folder);
        } catch (IOException ex) {
            log.error("agent_create_folder_failed userId={} name={}", userId, name, ex);
            throw new BusinessException(500, "新建文件夹失败，请稍后重试");
        }
        return name;
    }

    // ── 文件 ──────────────────────────────────────────────────

    /** 我的文件，新→旧；给了 sessionId 就只看那节课的。 */
    @Transactional(readOnly = true)
    public List<AiGeneratedFile> listFiles(Long userId, Long sessionId) {
        PageRequest limit = PageRequest.of(0, MAX_LIST);
        return sessionId == null
                ? fileRepository.findByOwnerIdOrderByUpdatedAtDescIdDesc(userId, limit)
                : fileRepository.findByOwnerIdAndSessionIdOrderByUpdatedAtDescIdDesc(userId, sessionId, limit);
    }

    /**
     * 智能体写文件：把 Markdown 按目标格式转换后落盘并入索引。
     *
     * @param filename 模型给的文件名。<b>扩展名会被改写</b>成目标格式的 ——
     *                 模型经常写 {@code 复习资料.md} 而老师选的是 Word，
     *                 若不改写，老师会拿到一个「后缀是 .docx、内容却是 Markdown」的文件，
     *                 双击打不开。真正决定格式的是老师的选择，不是模型的名字。
     */
    @Transactional
    public AiGeneratedFile writeFromMarkdown(Long userId, AgentFileContext context,
                                             String filename, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new BusinessException(400, "模型没有给出任何可写入的内容");
        }
        FileFormat format = context.format();
        String baseName = stripExtension(WorkspacePathSandbox.requireSafeFilename(filename));

        // ⚠ 必须对**拼上扩展名之后**的名字再校验一次，不能只校验模型给的原名。
        //   原名允许 200 字符，而「去掉旧扩展名、换成目标格式的扩展名」可能变长：
        //   200 字符的无扩展名 → 加上 ".docx" 就是 205，超过 filename 列宽 200。
        //   严格模式下 MySQL 会抛 errno 1406（不是静默截断），于是「文件已经落盘、
        //   索引行却没写进去」—— 盘上一个文件、库里查不到，是最难查的那类不一致。
        //   在这里拦住，模型会收到一句能看懂的提示并换个短名字重试。
        String finalName = WorkspacePathSandbox.requireSafeFilename(baseName + "." + format.extension());

        Path target = sandbox.resolveFile(userId, context.folder(), finalName);
        byte[] bytes = exporters.get(format).export(markdown, baseName);

        // ⚠ 顺序：**先落库、再写盘**。
        //   反过来（写盘 → 落库）的话，落库那一步失败会让事务回滚，
        //   而磁盘上的字节留了下来 —— 一个列不出来、也读不回来、还没人清理的孤儿文件。
        //   先落库则相反：写盘失败会连带把刚插入的行回滚掉，两边都干净。
        AiGeneratedFile saved = upsert(userId, context, finalName, format, 0L);

        try {
            Files.createDirectories(target.getParent());
            // NOFOLLOW_LINKS：万一在校验之后到写之前有人把目标换成一个链接
            // （需要本机文件系统权限），这一步会直接失败而不是顺着链接写出去。
            Files.write(target, bytes, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            log.error("agent_write_failed userId={} path={}", userId, target, ex);
            throw new BusinessException(500, "文件写入失败，请稍后重试");
        }

        long size = sizeOf(target);
        saved.setSizeBytes(size);
        saved = fileRepository.save(saved);

        log.info("agent_file_written userId={} folder={} filename={} format={} bytes={}",
                userId, context.folder(), finalName, format, size);

        // 留痕。放在这里而不是调用方：这是**所有 AI 写文件的唯一出口**，
        // 收口在此处，将来加第 5 个能写文件的工具也不会漏记。
        // 审计内部是 REQUIRES_NEW 且从不抛异常，写不进去也不会影响已经落盘的文件。
        auditService.record(userId, AdminAuditService.AI_FILE_WRITE,
                AdminAuditService.TARGET_AI_FILE, saved.getId(),
                saved.getFolder() + "/" + saved.getFilename() + "（" + format.name() + "，" + size + " 字节）");
        return saved;
    }

    /**
     * 同一个文件被重新生成时<b>改写成同一行</b>，而不是插一行新的。
     *
     * <p>不这样做的话，第二次生成会撞上唯一键
     * {@code (owner_id, folder, filename)} 报 500 —— 因为 MySQL 的 utf8mb4
     * 默认排序规则<b>不区分大小写</b>，{@code 复习资料.docx} 与 {@code 复习资料.DOCX}
     * 在库看来是同一个名字，而 {@code Files.write} 也确实会覆盖同一个文件。
     * 库里两行、盘上一个文件，是最难查的那类不一致。
     */
    private AiGeneratedFile upsert(Long userId, AgentFileContext context,
                                   String filename, FileFormat format, long size) {
        Optional<AiGeneratedFile> existing = fileRepository.findSameFile(
                userId, context.folder(), filename);

        AiGeneratedFile entity = existing.orElseGet(AiGeneratedFile::new);
        entity.setOwnerId(userId);
        entity.setFolder(context.folder());
        entity.setFilename(filename);
        entity.setFormat(format);
        entity.setSizeBytes(size);
        entity.setSessionId(context.sessionId());
        entity.setCoursewareId(context.coursewareId());
        entity.setPrompt(context.prompt());
        return fileRepository.save(entity);
    }

    /**
     * 读回<b>我自己生成</b>的文件（供工具 {@code files(filename)}）。
     *
     * <p>读取范围按 Friday 拍板只到「C：AI 自己生成的文件」——
     * 读不到别人的，也读不到课件原文件（那些走 {@code get_courseware_content}）。
     */
    @Transactional(readOnly = true)
    public String readMine(Long userId, String filename) {
        String safeName = WorkspacePathSandbox.requireSafeFilename(filename);
        List<AiGeneratedFile> matches = fileRepository.findMineByName(
                userId, safeName, PageRequest.of(0, 1));
        if (matches.isEmpty()) {
            throw new BusinessException(404, "没有找到你生成过的文件：" + safeName);
        }
        AiGeneratedFile file = matches.get(0);

        // Word / Excel 是二进制，当作文本读出来是一堆乱码。
        // 与其回一段乱码，不如说清楚「读不了、但文件在、可以下载」——
        // 模型拿到这句才知道该告诉老师去下载，而不是把那堆乱码当成内容继续用。
        if (file.getFormat() == FileFormat.DOCX || file.getFormat() == FileFormat.XLSX) {
            return String.format("《%s》是 %s 文件，不能作为文字读取；它就在「%s」文件夹里，可以由老师直接下载。",
                    file.getFilename(), file.getFormat(), file.getFolder());
        }

        Path path = sandbox.resolveFile(userId, file.getFolder(), file.getFilename());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, "文件已不在磁盘上：" + file.getFilename());
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.length() <= MAX_READ_CHARS) {
                return content;
            }
            return content.substring(0, MAX_READ_CHARS)
                    + "\n\n…（内容过长，以上为前 " + MAX_READ_CHARS + " 个字符）";
        } catch (IOException ex) {
            log.error("agent_read_failed userId={} path={}", userId, path, ex);
            throw new BusinessException(500, "读取文件失败");
        }
    }

    /** 下载用：按 id 取<b>我的</b>文件，并解析出磁盘路径。 */
    @Transactional(readOnly = true)
    public Download resolveForDownload(Long userId, Long fileId) {
        AiGeneratedFile file = fileRepository.findByIdAndOwnerId(fileId, userId)
                // 找不到与不是自己的，返回同一句话：否则可以靠错误信息的差异
                // 探测出「这个 id 存在但不是你的」，等于把别人有哪些文件告诉了攻击者。
                .orElseThrow(() -> new BusinessException(404, "文件不存在"));
        Path path = sandbox.resolveFile(userId, file.getFolder(), file.getFilename());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, "文件已不在磁盘上");
        }
        return new Download(file, path);
    }

    /** 下载所需的一对：索引记录 + 磁盘路径。 */
    public record Download(AiGeneratedFile file, Path path) {
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 去掉最后一个扩展名；没有扩展名就原样返回。{@code a.b.md} → {@code a.b}。 */
    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return filename;
        }
        return filename.substring(0, dot);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            // 大小只是展示用，取不到不该让整个写入失败
            return 0L;
        }
    }
}
