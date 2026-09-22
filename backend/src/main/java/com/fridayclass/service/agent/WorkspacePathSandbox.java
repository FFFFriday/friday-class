package com.fridayclass.service.agent;

import com.fridayclass.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 智能体的<b>路径沙箱</b>：全项目<b>唯一</b>允许把用户/模型提供的字符串变成磁盘路径的地方。
 *
 * <h2>为什么要有这个类（而不是在每个工具里各写一遍校验）</h2>
 *
 * 这是整个项目里<b>第一个「模型能产生副作用」的地方</b>。此前所有 AI 调用都是只读的：
 * 提示词写歪了，最坏是回答得不好。有了写文件之后，一次路径拼接失误就是
 * 「往 {@code C:\Windows} 里写东西」。
 *
 * <p>而这类校验最容易出的问题不是「写错」，是<b>漏写</b>——将来加第 5 个工具时
 * 顺手 {@code Files.write(root.resolve(name), ...)}，没有任何报错提醒你。
 * 所以收口成一处：<b>评审时只要搜「谁没经过 Sandbox」就够了</b>。
 *
 * <h2>目录布局</h2>
 * <pre>
 * {storage.root}/ai-workspace/u{userId}/{文件夹}/{文件名}
 *                              └── 每个用户一个根，天然隔离
 * </pre>
 *
 * <h2>核心立场：文件夹名与文件名都<b>只是「名字」，不是路径</b></h2>
 *
 * 这一条本身就挡住了大半攻击 —— {@code ..}、{@code C:\}、{@code /etc/} 根本进不来，
 * 因为它们都不是「一个名字」。但即便如此，下面七条仍然逐条检查，
 * 不允许出现「因为名字短所以安全」这种推理。
 *
 * <h2>七条绕过手法，逐条对应</h2>
 * <ol>
 *   <li><b>{@code ..} 穿越</b> —— 名字里出现路径分隔符或恰好是 {@code .} / {@code ..} 一律拒；</li>
 *   <li><b>符号链接 / 目录联接逃逸</b> —— 名字合法，但那个「文件夹」其实是个指向外部的链接。
 *       光靠字符串判断<b>永远查不出来</b>，必须落到 {@link Path#toRealPath()} 上比对真实位置；</li>
 *   <li><b>绝对路径</b> —— {@code C:\x}、{@code \\server\share}、{@code /etc/passwd}
 *       都含分隔符或冒号，被第 1 条与非法字符拦掉；</li>
 *   <li><b>Windows 保留设备名</b> —— {@code CON} / {@code NUL} / {@code COM1} 这类名字，
 *       即使加了扩展名（{@code CON.txt}）在 Windows 上仍指向设备。写进去不是报错就是挂起；</li>
 *   <li><b>非法字符</b> —— {@code < > : " | ? *} 与控制字符；</li>
 *   <li><b>大小写不敏感</b> —— Windows 上 {@code Report.docx} 与 {@code report.docx}
 *       是<b>同一个文件</b>。这里只能查名字，真正的重名归并见
 *       {@link WorkspaceFileService}（它按忽略大小写去重后再落库）；</li>
 *   <li><b>结尾的点与空格</b> —— Windows 会<b>静默</b>把它们去掉，
 *       于是 {@code "报告."} 与 {@code "报告"} 变成同一个文件，
 *       而数据库里却是两行。必须当场拒掉。</li>
 * </ol>
 *
 * <p><b>不做的事</b>：这里只判断「这个名字能不能用、最终落在哪」，
 * 不创建目录、不写文件、不碰数据库 —— 保持成一个纯函数才好写单测。
 */
@Component
public class WorkspacePathSandbox {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePathSandbox.class);

    /** 智能体工作区在存储根下的固定子目录。 */
    public static final String WORKSPACE_DIR = "ai-workspace";

    /** 与 {@code ai_generated_file.folder} 的列宽一致。 */
    private static final int MAX_FOLDER_CHARS = 100;
    /** 与 {@code ai_generated_file.filename} 的列宽一致。 */
    private static final int MAX_FILENAME_CHARS = 200;
    /**
     * 整个绝对路径的长度上限。
     *
     * <p>留了余量而不是卡满 260：Windows 传统 API 的 {@code MAX_PATH} 是 260，
     * 超了会在写文件时抛一个与「名字太长」毫无关系的 {@code IOException}，
     * 排查起来很费劲。这里提前给出能看懂的提示。
     */
    private static final int MAX_ABS_PATH_CHARS = 240;

    /** 控制字符（含 \t \n）与 Windows 保留字符。冒号同时覆盖 {@code C:} 与 NTFS 数据流。 */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[<>:\"|?*\\x00-\\x1F]");

    /** Windows 保留设备名。加了扩展名也仍然是设备（{@code CON.txt} 同理），故按第一个点之前判断。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path storageRoot;

    public WorkspacePathSandbox(@Value("${app.storage.root:./storage}") String rootDir) {
        this.storageRoot = Paths.get(rootDir).toAbsolutePath().normalize();
        log.info("agent_sandbox_ready storageRoot={} workspaceRoot={}",
                this.storageRoot, this.storageRoot.resolve(WORKSPACE_DIR));
    }

    /** 智能体工作区根（{@code {storage}/ai-workspace}）。 */
    public Path workspaceRoot() {
        return storageRoot.resolve(WORKSPACE_DIR);
    }

    /**
     * 某个用户的工作区根。
     *
     * <p>{@code userId} 来自登录态（{@code Long}），不是模型给的字符串，
     * 所以这里没有注入面 —— 但仍然用 {@code u} 前缀隔开，
     * 是为了让目录列表一眼能看出「这层是权限边界」。
     */
    public Path userRoot(Long userId) {
        requireUserId(userId);
        return workspaceRoot().resolve("u" + userId).normalize();
    }

    /**
     * 校验一个「名字」（文件夹名或文件名），返回去掉首尾空白后的结果。
     *
     * @param label 出错提示里用的称呼，如「文件夹名」「文件名」
     * @throws BusinessException 名字不合法。用 400 而不是 500：这是调用方给了坏输入，
     *                          而且智能体那边会把它<b>当作工具结果回给模型</b>让它自己改
     */
    public static String requireSafeName(String raw, String label, int maxChars) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, label + "不能为空");
        }
        String name = raw.strip();

        if (name.codePointCount(0, name.length()) > maxChars) {
            throw new BusinessException(400, label + "最长 " + maxChars + " 个字符");
        }

        // ① 路径分隔符：出现任何一个，说明它想当「路径」而不是「名字」
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new BusinessException(400, label + "不能包含路径分隔符（/ 或 \\）");
        }
        // ② 恰好是 . 或 .. —— 它们是目录自身的指代，拼进路径会改写落点
        if (".".equals(name) || "..".equals(name)) {
            throw new BusinessException(400, label + "不能是「.」或「..」");
        }
        // ③ 非法字符
        if (ILLEGAL_CHARS.matcher(name).find()) {
            throw new BusinessException(400, label + "含有不能用于文件名的字符（< > : \" | ? * 等）");
        }
        // ④ 结尾的点与空格：Windows 会静默去掉，导致「两个名字其实是一个文件」
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw new BusinessException(400, label + "不能以点或空格结尾");
        }
        // ⑤ 保留设备名（只看第一个点之前的部分，CON.txt 也是设备）
        int dot = name.indexOf('.');
        String base = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(base)) {
            throw new BusinessException(400, label + "是系统保留名（如 CON / NUL / COM1），不能使用");
        }

        return name;
    }

    /** 校验文件夹名。 */
    public static String requireSafeFolder(String raw) {
        return requireSafeName(raw, "文件夹名", MAX_FOLDER_CHARS);
    }

    /** 校验文件名。 */
    public static String requireSafeFilename(String raw) {
        return requireSafeName(raw, "文件名", MAX_FILENAME_CHARS);
    }

    /**
     * 解析「某用户的某个文件夹」的绝对路径，并确认它没有逃出该用户的工作区。
     *
     * <p>这是 7 条里第 2 条的落点：字符串层面全部合法，
     * 但 {@code ai-workspace/u3/本课资料} 这个目录本身可能是一个
     * 指向 {@code C:\Windows} 的<b>目录联接</b>。只有把路径交给文件系统
     * 解析出<b>真实位置</b>才能发现。
     */
    public Path resolveFolder(Long userId, String folder) {
        Path root = userRoot(userId);
        Path target = root.resolve(requireSafeFolder(folder)).normalize();
        assertInside(target, root, "文件夹");
        return target;
    }

    /**
     * 解析「某用户的某个文件夹下的某个文件」的绝对路径，并做同样的逃逸检查。
     */
    public Path resolveFile(Long userId, String folder, String filename) {
        Path root = userRoot(userId);
        Path target = root.resolve(requireSafeFolder(folder)).resolve(requireSafeFilename(filename)).normalize();
        assertInside(target, root, "文件路径");
        return target;
    }

    /**
     * 解析用户工作区根，并做逃逸检查（{@code u{id}} 这一层自己也可能被人做成联接）。
     */
    public Path resolveUserRoot(Long userId) {
        Path root = userRoot(userId);
        assertInside(root, root, "工作区");
        return root;
    }

    /** 造一个可读的「相对工作区」展示路径，用于日志与错误提示。 */
    public String describe(Long userId, String folder, String filename) {
        return WORKSPACE_DIR + "/u" + userId + "/" + folder + "/" + filename;
    }

    // ── 内部 ──────────────────────────────────────────────────

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(400, "缺少用户标识");
        }
    }

    /**
     * 确认 {@code target} 落在 {@code root} 之内 —— 先做字符串判断，再落到真实路径上比对。
     *
     * <h3>为什么要「找到最深的已存在祖先」再取真实路径</h3>
     *
     * {@code toRealPath()} 要求路径<b>已经存在</b>；而我们要写的文件通常还不存在。
     * 若因此跳过检查，等于对「新建文件」这条最常走的路径不设防。
     *
     * <p>所以从目标往上找<b>第一个真实存在的祖先</b>，对它取真实路径。
     * 这恰好覆盖了真正危险的情形：中间某一层（尤其是那个「文件夹」）
     * 是个指向外部的目录联接 —— 它一定存在，因此一定会被解析出来。
     *
     * <p>{@code TargetNotExist} 之外还有 {@link IOException}（权限不足等）：
     * 同样按拒绝处理。<b>校验本身出错时必须拒绝，不能放行</b>——
     * 「检查不出来就算了」正是这类防护最常见的死法。
     */
    private void assertInside(Path target, Path root, String label) {
        // 第一层：纯字符串。挡掉 .. 与绝对路径，代价极低，且不碰磁盘。
        if (!target.startsWith(root)) {
            log.warn("agent_path_escape_lexical target={} root={}", target, root);
            throw new BusinessException(400, label + "不合法");
        }
        if (target.toString().length() > MAX_ABS_PATH_CHARS) {
            throw new BusinessException(400, label + "过长，请把文件夹或文件名改短一些");
        }

        // 第二层：真实路径比对，以**存储根**为锚。
        //
        // ⚠ 锚点必须是 storageRoot，不能是 root。原因是「首次使用」：
        //   ai-workspace/u3 在老师第一次用之前根本不存在，
        //   而对不存在的路径调 toRealPath() 会直接抛异常 ——
        //   若拿 root 当锚，**第一次写文件必然失败**，且报的还是一句
        //   与真实原因毫无关系的「路径不合法」。
        //   存储根则一定存在（里面已经有 courseware/ 与 slides/）。
        Path realStorage = realPathOrReject(storageRoot, label, 500);

        Path probe = target;
        // 用 NOFOLLOW_LINKS 找「存在的最深一层」：这样即使某一层是个
        // 断掉的目录联接（目标已不存在），也会被认出来并交给 toRealPath 去解析，
        // 而不是被当成「不存在」跳过 —— 跳过就等于放行。
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            log.error("agent_path_no_existing_ancestor target={} root={}", target, root);
            throw new BusinessException(500, "存储目录不可用，请联系管理员");
        }

        Path realProbe = realPathOrReject(probe, label, 400);
        if (!realProbe.startsWith(realStorage)) {
            // 走到这里说明字符串层面完全合法，但磁盘上某一层是联接/符号链接。
            // 这是最隐蔽的一种绕过，日志要留全：目标、解析前的中间层、真实位置。
            log.warn("agent_path_escape_real target={} probe={} realProbe={} realStorage={}",
                    target, probe, realProbe, realStorage);
            throw new BusinessException(400, label + "不合法");
        }

        rejectLinksUnderWorkspace(target, label);
    }

    /**
     * 工作区<b>内部</b>不允许出现任何符号链接或目录联接。
     *
     * <h3>为什么仅靠上面那条「真实路径要落在存储根之内」不够</h3>
     *
     * 那条检查的锚点是**存储根**。于是 `ai-workspace/u7/本课资料` 若被做成一个
     * 指向 `ai-workspace/u8` 的目录联接，真实路径仍然「在存储根之内」——
     * 检查通过，而 7 号老师就写进了 8 号老师的工作区。
     *
     * <p>把联接指向存储根之外的目录能被挡住（测试里就是这么造的），
     * 但只要指向**内部另一个用户**，就绕过去了。这正是「用户之间互相隔离」
     * 这条承诺的破口。
     *
     * <h3>为什么可以一律拒绝，而不必判断指向哪里</h3>
     *
     * `ai-workspace` 下面每一层都是<b>我们自己建的</b>（用户根 + 老师起的文件夹名）。
     * 合法的使用场景里，这里永远不该出现链接。
     * 所以规则可以写得比「链接不能指向外面」更强：<b>一个都不许有</b>。
     * 规则越简单越不容易漏。
     *
     * <h3>为什么不能只查 isSymbolicLink</h3>
     *
     * Windows 的<b>目录联接（junction）不是符号链接</b>：{@code isSymbolicLink()}
     * 对它返回 false。必须先 NOFOLLOW 取属性，再用 {@code isOther()}
     * 认出这个「既不是普通文件、也不是普通目录」的重解析点。
     * 只查 isSymbolicLink 的话，恰好把最需要防的那一种放过去。
     */
    private void rejectLinksUnderWorkspace(Path target, String label) {
        Path workspace = workspaceRoot();
        if (!target.startsWith(workspace)) {
            // 理论上到不了（前面已经做过词法包含检查），留一手
            throw new BusinessException(400, label + "不合法");
        }
        // 从 workspace 的下一层开始逐层检查到目标本身
        Path current = workspace;
        for (Path segment : workspace.relativize(target)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;   // 还没建出来，不可能是链接
            }
            try {
                if (Files.isSymbolicLink(current)
                        || Files.readAttributes(current, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS).isOther()) {
                    log.warn("agent_path_link_rejected target={} linkAt={}", target, current);
                    throw new BusinessException(400, label + "不合法");
                }
            } catch (IOException ex) {
                log.warn("agent_path_link_check_failed path={} reason={}", current, ex.toString());
                throw new BusinessException(400, label + "不合法");
            }
        }
    }

    /**
     * 取真实路径；取不到就按 {@code failCode} 拒绝。
     *
     * <p>{@code failCode} 分两种：对用户给的路径用 400（他改个名字就好），
     * 对存储根用 500（部署有问题，用户改什么都没用）。
     */
    private Path realPathOrReject(Path path, String label, int failCode) {
        try {
            return path.toRealPath();
        } catch (IOException | InvalidPathException ex) {
            log.warn("agent_path_unresolvable path={} reason={}", path, ex.toString());
            throw new BusinessException(failCode,
                    failCode == 500 ? "存储目录不可用，请联系管理员" : label + "不合法");
        }
    }
}
