package com.fridayclass.service.agent;

import com.fridayclass.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径沙箱的用例集。
 *
 * <h3>为什么这个测试类值得写这么细</h3>
 *
 * 这是全项目<b>第一个「模型能产生副作用」的地方</b>。
 * 沙箱漏一条的后果不是「功能不好用」，而是模型能往
 * {@code C:\Windows}、别人的工作区、或者任意路径写东西。
 * 而这类校验最容易出的问题恰恰是<b>漏写</b>——没有报错提醒你少了哪一条。
 *
 * <p>所以七种绕过手法每一种都有独立用例，并且断言的是
 * <b>业务码 400</b>（而不是「抛了某个异常」）——
 * 400 表示「调用方给了坏输入」，前端与工具回填都按这个码分支。
 */
class WorkspacePathSandboxTest {

    /** 存储根。放在临时目录下的 storage/，与它平级的 outside/ 用来模拟「外部目录」。 */
    private Path tempDir;
    private Path storageRoot;
    private Path outsideDir;
    private WorkspacePathSandbox sandbox;

    @TempDir
    Path tempRoot;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = tempRoot;
        storageRoot = Files.createDirectories(tempDir.resolve("storage"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outsideDir.resolve("机密.txt"), "不该被读到", StandardCharsets.UTF_8);
        sandbox = new WorkspacePathSandbox(storageRoot.toString());
    }

    // ── 正常路径 ──────────────────────────────────────────────

    @Nested
    @DisplayName("正常输入应当通过")
    class 正常输入 {

        @Test
        @DisplayName("普通中文文件名")
        void acceptsChineseName() {
            assertEquals("复习资料.docx", WorkspacePathSandbox.requireSafeFilename("复习资料.docx"));
        }

        @Test
        @DisplayName("首尾空白会被去掉（模型经常多打空格）")
        void trimsWhitespace() {
            assertEquals("复习资料.md", WorkspacePathSandbox.requireSafeFilename("  复习资料.md  "));
        }

        @Test
        @DisplayName("点与括号等常见符号是允许的")
        void acceptsDotsAndDashes() {
            assertEquals("第1-3节(复习).md",
                    WorkspacePathSandbox.requireSafeFilename("第1-3节(复习).md"));
        }

        @Test
        @DisplayName("解析出的路径确实落在该用户的工作区里")
        void resolvesInsideUserRoot() {
            Path file = sandbox.resolveFile(7L, "本课资料", "复习资料.md");
            assertNotNull(file);
            assertTrue(file.startsWith(sandbox.userRoot(7L)));
            assertTrue(file.toString().endsWith("复习资料.md"));
        }

        @Test
        @DisplayName("不同用户解析出的根互不相同（隔离的第一层）")
        void userRootsAreDistinct() {
            assertFalse(sandbox.userRoot(1L).equals(sandbox.userRoot(2L)));
            assertTrue(sandbox.userRoot(1L).endsWith("u1"));
            assertTrue(sandbox.userRoot(2L).endsWith("u2"));
        }

        @Test
        @DisplayName("目录还不存在时也能解析（老师第一次使用的情形）")
        void resolvesWhenDirectoriesDoNotExistYet() {
            // 注意：这里刻意不预先创建 ai-workspace/u99
            Path file = sandbox.resolveFile(99L, "我的资料", "第一个文件.md");
            assertTrue(file.startsWith(sandbox.workspaceRoot()));
        }

        @Test
        @DisplayName("首次使用时不存在的用户根也能解析（曾是真实缺陷）")
        void resolvesUserRootWhenMissing() {
            // 早期实现拿用户根当真实路径锚点，而 toRealPath() 对不存在的路径会抛异常，
            // 导致「老师第一次用就写不了文件」。锚点改成存储根之后这条才成立。
            Path root = sandbox.resolveUserRoot(12345L);
            assertEquals(sandbox.workspaceRoot().resolve("u12345"), root);
        }
    }

    // ── 第一条：.. 穿越 ───────────────────────────────────────

    @Nested
    @DisplayName("① 目录穿越 ..")
    class 目录穿越 {

        @Test
        @DisplayName("文件名恰好是 .. 被拒")
        void rejectsDotDot() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename(".."));
        }

        @Test
        @DisplayName("文件名恰好是 . 被拒")
        void rejectsSingleDot() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("."));
        }

        @Test
        @DisplayName("夹在中间的 .. 也直接拒（因为含路径分隔符）")
        void rejectsEmbeddedTraversal() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("../../etc/passwd"));
        }

        @Test
        @DisplayName("文件夹名同样拒绝 ..")
        void rejectsTraversalInFolder() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFolder(".."));
        }
    }

    // ── 第二条：分隔符与绝对路径 ──────────────────────────────

    @Nested
    @DisplayName("② 路径分隔符 / 绝对路径")
    class 分隔符 {

        @Test
        @DisplayName("正斜杠被拒")
        void rejectsForwardSlash() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("a/b.md"));
        }

        @Test
        @DisplayName("反斜杠被拒")
        void rejectsBackslash() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("a\\b.md"));
        }

        @Test
        @DisplayName("Windows 绝对路径被拒")
        void rejectsWindowsAbsolutePath() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("C:\\Windows\\win.ini"));
        }

        @Test
        @DisplayName("UNC 网络路径被拒")
        void rejectsUncPath() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("\\\\server\\share\\x.md"));
        }

        @Test
        @DisplayName("Unix 绝对路径被拒")
        void rejectsUnixAbsolutePath() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("/etc/passwd"));
        }

        @Test
        @DisplayName("NTFS 数据流写法（file:stream）被拒")
        void rejectsAlternateDataStream() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告.md:hidden"));
        }
    }

    // ── 第三条：非法字符 ──────────────────────────────────────

    @Nested
    @DisplayName("③ 非法字符")
    class 非法字符 {

        @Test
        @DisplayName("七个保留字符逐个被拒")
        void rejectsEachReservedCharacter() {
            for (String ch : List.of("<", ">", ":", "\"", "|", "?", "*")) {
                assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告" + ch + ".md"));
            }
        }

        @Test
        @DisplayName("换行符被拒（否则能靠它伪造日志行）")
        void rejectsNewline() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告\n伪造日志.md"));
        }

        @Test
        @DisplayName("制表符被拒")
        void rejectsTab() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告\t.md"));
        }
    }

    // ── 第四条：Windows 保留设备名 ────────────────────────────

    @Nested
    @DisplayName("④ Windows 保留设备名")
    class 保留设备名 {

        @Test
        @DisplayName("裸保留名被拒")
        void rejectsBareReservedName() {
            for (String name : List.of("CON", "PRN", "AUX", "NUL", "COM1", "LPT9")) {
                assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename(name));
            }
        }

        @Test
        @DisplayName("带扩展名的保留名也被拒（CON.txt 在 Windows 上仍是设备）")
        void rejectsReservedNameWithExtension() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("CON.txt"));
        }

        @Test
        @DisplayName("保留名不区分大小写")
        void reservedNameIsCaseInsensitive() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("nul.md"));
        }

        @Test
        @DisplayName("名字里含 CON 但是普通词，不受影响")
        void allowsNamesContainingReservedWord() {
            assertEquals("CONCEPT.md", WorkspacePathSandbox.requireSafeFilename("CONCEPT.md"));
        }
    }

    // ── 第五条：结尾的点与空格 ────────────────────────────────

    @Nested
    @DisplayName("⑤ 结尾的点与空格")
    class 结尾字符 {

        @Test
        @DisplayName("以点结尾被拒（Windows 会静默去掉它）")
        void rejectsTrailingDot() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告."));
        }

        @Test
        @DisplayName("以空格结尾被拒")
        void rejectsTrailingSpace() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("报告 ."));
        }
    }

    // ── 第六条：空与超长 ──────────────────────────────────────

    @Nested
    @DisplayName("⑥ 空值与超长")
    class 空与超长 {

        @Test
        @DisplayName("null 被拒")
        void rejectsNull() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename(null));
        }

        @Test
        @DisplayName("全空白被拒")
        void rejectsBlank() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("    "));
        }

        @Test
        @DisplayName("文件名超过 200 字符被拒（与数据库列宽一致）")
        void rejectsTooLongFilename() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFilename("文".repeat(201)));
        }

        @Test
        @DisplayName("文件夹名超过 100 字符被拒")
        void rejectsTooLongFolder() {
            assertRejected400(() -> WorkspacePathSandbox.requireSafeFolder("夹".repeat(101)));
        }
    }

    // ── 第七条：符号链接 / 目录联接逃逸 ───────────────────────
    //
    // 这一条是**唯一无法靠字符串判断**的：名字完全合法，
    // 但那个「文件夹」在磁盘上是一个指向外部的联接。
    // 所以必须真造一个出来测，不能靠推理说「应该能挡住」。

    @Nested
    @DisplayName("⑦ 目录联接（junction）逃逸 —— 真造一个测")
    class 联接逃逸 {

        @Test
        @DisplayName("文件夹是指向外部目录的联接 → 拒（否则能写到工作区外）")
        void rejectsJunctionEscape() throws Exception {
            Path userRoot = Files.createDirectories(sandbox.userRoot(7L));
            Path link = userRoot.resolve("本课资料");
            createJunction(link, outsideDir);

            // 前提校验：联接真的建出来了，否则这个用例等于没测
            assertTrue(Files.isDirectory(link), "目录联接未创建成功，用例前提不成立");
            assertTrue(Files.exists(link.resolve("机密.txt")), "联接没有指向预期目录");

            assertRejected400(() -> sandbox.resolveFile(7L, "本课资料", "x.md"));
        }

        @Test
        @DisplayName("文件夹本身合法时，写入路径仍落在工作区内")
        void normalFolderStillWorks() {
            Path file = sandbox.resolveFile(7L, "本课资料", "x.md");
            assertTrue(file.startsWith(sandbox.userRoot(7L)));
        }

        @Test
        @DisplayName("用户根自身被替换成联接 → 拒")
        void rejectsJunctionAtUserRoot() throws Exception {
            Path workspace = Files.createDirectories(sandbox.workspaceRoot());
            Path link = workspace.resolve("u8");
            createJunction(link, outsideDir);

            assertTrue(Files.isDirectory(link), "目录联接未创建成功，用例前提不成立");
            assertRejected400(() -> sandbox.resolveUserRoot(8L));
        }

        @Test
        @DisplayName("联接指向「另一个用户的工作区」也要拒 —— 锚点只看存储根会漏掉这一种")
        void rejectsJunctionIntoAnotherUser() throws Exception {
            // 这是最容易被漏掉的一种：目标**仍在存储根之内**，
            // 所以「真实路径有没有跑出 storage」这条检查完全通过，
            // 而 7 号老师却写进了 8 号老师的目录。
            Path workspace = Files.createDirectories(sandbox.workspaceRoot());
            Path u7 = Files.createDirectories(workspace.resolve("u7"));
            Path u8 = Files.createDirectories(workspace.resolve("u8"));
            Files.writeString(u8.resolve("别人的东西.md"), "不该被 7 号读到", StandardCharsets.UTF_8);

            Path link = u7.resolve("本课资料");
            createJunction(link, u8);

            assertTrue(Files.isDirectory(link), "目录联接未创建成功，用例前提不成立");
            assertRejected400(() -> sandbox.resolveFile(7L, "本课资料", "x.md"));
            assertRejected400(() -> sandbox.resolveFolder(7L, "本课资料"));
        }

        @Test
        @DisplayName("联接指向 storage 内的其他目录（如课件目录）也要拒")
        void rejectsJunctionIntoCoursewareDir() throws Exception {
            Path courseware = Files.createDirectories(storageRoot.resolve("courseware"));
            Files.writeString(courseware.resolve("某课件.pptx"), "假装是课件", StandardCharsets.UTF_8);

            Path userRoot = Files.createDirectories(sandbox.userRoot(9L));
            createJunction(userRoot.resolve("资料"), courseware);

            assertRejected400(() -> sandbox.resolveFile(9L, "资料", "x.md"));
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────

    /**
     * 用 {@code mklink /J} 建目录联接。
     *
     * <p>刻意不用 {@code Files.createSymbolicLink}：符号链接在 Windows 上
     * 需要管理员权限或开发者模式，而<b>目录联接不需要</b>（它是 NTFS 的
     * 重解析点，普通用户可建）。用联接才能保证这个用例在普通权限下也能跑。
     *
     * <p>建不出来就直接断言失败，而不是 skip —— 一个静默跳过的安全用例
     * 比没有这个用例更危险，它会让人以为「验过了」。
     */
    private static void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "创建目录联接失败：" + output);
    }

    private static void assertRejected400(org.junit.jupiter.api.function.Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(400, ex.getCode(), "应当以业务码 400 拒绝，实际：" + ex.getCode());
    }
}
