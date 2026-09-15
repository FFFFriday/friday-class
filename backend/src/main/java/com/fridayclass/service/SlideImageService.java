package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.Courseware;
import com.fridayclass.repository.CoursewareRepository;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 把 .pptx 的某一页渲染成 PNG 图片。
 *
 * <h3>为什么需要它</h3>
 * F001 生成的那份 {@code page{n}.html}（见 {@link PptxConverter#toSlideHtml}）只是把
 * 文字倒进一个白底 div —— 它从来没「渲染 PPT」，因为 POI 抽文字拿不到图片、配色、
 * 位置和形状。所以老师上课时看到的是一片纯文字，而不是自己的课件。
 *
 * <p>这里改用 POI 的 {@code XSLFSlide.draw(Graphics2D)}，把整页**画**到一张
 * {@link BufferedImage} 上再编码成 PNG：图片、母版背景、配色、形状、二维码全部还原。
 *
 * <h3>为什么按需渲染而不是上传时就全部渲染</h3>
 * 实测一份 79 页课件：打开 pptx 约 830ms，之后**每页只要 70–170ms**，
 * 单页 PNG 约 70KB。按需渲染意味着：
 * <ul>
 *   <li>上传接口不必多等十几秒；</li>
 *   <li>已有课件（上传这个功能之前传的）不用写迁移脚本，第一次被看到时自动补上；</li>
 *   <li>没被翻到过的页永远不渲染，不浪费磁盘。</li>
 * </ul>
 * 渲染结果落盘后永久复用，所以一节课里每页最多只渲染一次。
 *
 * <h3>并发</h3>
 * 同一页可能被老师端 + 几十个学生端**同时**请求（翻页广播一发出就都来拉图）。
 * 用「每个课件一把锁」把并发的重复渲染合并成一次，其余请求等锁后直接读文件。
 */
@Service
public class SlideImageService {

    private static final Logger log = LoggerFactory.getLogger(SlideImageService.class);

    static {
        // 与 FileStorageService / PptxConverter 同一条规矩：打开用户上传的压缩包之前，
        // 必须先设好防 zip bomb 的参数。幂等，重复调用无副作用。
        PptxZipSecurity.apply();
    }

    /**
     * 渲染倍率。PPT 默认页面是 720×405 点，×2 得到 1440×810 ——
     * 投影仪和高分屏都够清晰，单页 PNG 也才 70KB 左右。
     */
    private static final int SCALE = 2;

    /**
     * 字体名 → {@link Font} 的缓存。
     *
     * <p>缓存的是 <b>Font 对象</b>本身（构造它要查系统字体注册表），
     * 而**不是**「能不能画出某段字」的结论——那个结论依赖具体文字：
     * Arial 画得出 {@code é} 却画不出「成绩」，按字体缓存结论会得出错误答案。
     * {@code canDisplayUpTo} 每次照实判断，它对短字符串本来就很快。
     */
    private static final Map<String, Font> FONT_CACHE = new ConcurrentHashMap<>();

    /**
     * 找不到字体时的兜底候选，按优先级排列。
     *
     * <p>Windows 在前（本机演示环境），Linux 服务器在后（Noto / 文泉驿是常见的中文包）。
     * 末尾的 {@link Font#SANS_SERIF} 是 Java **逻辑字体**，任何 JVM 上必然存在，
     * 且带字体链接、能绘制中文——作为最后的保险。
     */
    private static final List<String> CJK_FALLBACKS = List.of(
            "Microsoft YaHei", "微软雅黑",
            "SimSun", "宋体",
            "SimHei", "黑体",
            "Noto Sans CJK SC", "Source Han Sans CN",
            "WenQuanYi Zen Hei",
            Font.SANS_SERIF);

    private final FileStorageService fileStorageService;
    private final CoursewareRepository coursewareRepository;

    /**
     * 每个课件一把锁，避免同一页被并发渲染多次。
     *
     * <p>条目数等于课件数（很小），故不做清理；若将来课件量级上来，
     * 这里应换成带淘汰的缓存或直接用文件锁。
     */
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SlideImageService(FileStorageService fileStorageService,
                             CoursewareRepository coursewareRepository) {
        this.fileStorageService = fileStorageService;
        this.coursewareRepository = coursewareRepository;
    }

    /**
     * 取某一页的幻灯片图片；还没有就现渲染一张并落盘。
     *
     * @return PNG 的绝对路径（保证位于存储根目录内、且文件已完整写入）
     */
    public Path ensureImage(Long coursewareId, int pageNo) {
        Path target = imagePath(coursewareId, pageNo);

        // 快路径：绝大多数请求（同一页被第二个学生拉）走到这里就返回了
        if (Files.exists(target)) {
            return target;
        }

        ReentrantLock lock = locks.computeIfAbsent(coursewareId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 双重检查：等锁期间可能已经被别的线程渲染好了
            if (Files.exists(target)) {
                return target;
            }
            renderPage(coursewareId, pageNo, target);
            return target;
        } finally {
            lock.unlock();
        }
    }

    private Path imagePath(Long coursewareId, int pageNo) {
        // 走 FileStorageService.resolve 而不是自己拼路径：那里已经做了
        // 「最终路径必须在存储根目录内」的校验，不必重复实现一遍。
        return fileStorageService.resolve(
                "slides/" + coursewareId + "/page" + pageNo + ".png");
    }

    private void renderPage(Long coursewareId, int pageNo, Path target) {
        Courseware courseware = coursewareRepository.findById(coursewareId)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));

        String filePath = courseware.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new BusinessException(404, "课件文件已丢失");
        }
        Path pptx = fileStorageService.resolve(filePath);

        long started = System.currentTimeMillis();
        try {
            Files.createDirectories(target.getParent());

            // 先写临时文件、成功了再改名。直接写目标文件的话，并发的读者
            // （不同 JVM 的实例，或锁被误删之后）可能读到只写了一半的 PNG，
            // 浏览器拿到坏图就再也不重试了。
            Path temp = Files.createTempFile(target.getParent(), "page" + pageNo + "-", ".part");
            try {
                try (InputStream in = Files.newInputStream(pptx);
                     XMLSlideShow ppt = new XMLSlideShow(in)) {

                    List<XSLFSlide> slides = ppt.getSlides();
                    if (pageNo < 1 || pageNo > slides.size()) {
                        throw new BusinessException(404,
                                "第 " + pageNo + " 页不存在（本课件共 " + slides.size() + " 页）");
                    }
                    drawToPng(ppt, slides.get(pageNo - 1), temp);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                // 改名成功后这里是无操作；渲染中途失败时清掉残留的临时文件
                Files.deleteIfExists(temp);
            }
        } catch (BusinessException ex) {
            // 必须排在 RuntimeException 前面：BusinessException 也是 RuntimeException，
            // 否则「第 N 页不存在」会被下面统一改写成 500。
            throw ex;
        } catch (IOException ex) {
            log.error("slide_render_io_failed coursewareId={} pageNo={}", coursewareId, pageNo, ex);
            throw new BusinessException(500, "幻灯片渲染失败");
        } catch (RuntimeException ex) {
            // POI 遇到畸形形状会抛运行时异常。这不是「课件不存在」，但同样不该把
            // 堆栈直接甩给前端，统一转成可读的业务错误并记全日志。
            log.error("slide_render_failed coursewareId={} pageNo={}", coursewareId, pageNo, ex);
            throw new BusinessException(500, "幻灯片渲染失败");
        }

        log.info("slide_rendered coursewareId={} pageNo={} costMs={}",
                coursewareId, pageNo, System.currentTimeMillis() - started);
    }

    /** 把一页画到 PNG 文件。 */
    private void drawToPng(XMLSlideShow ppt, XSLFSlide slide, Path target) throws IOException {
        Dimension2D size = ppt.getPageSize();
        int width = (int) Math.round(size.getWidth() * SCALE);
        int height = (int) Math.round(size.getHeight() * SCALE);

        // TYPE_INT_RGB 而不是带透明通道的类型：PNG 带透明时，浏览器在深色主题下
        // 会把透明区域当黑/灰，而 PPT 页面本身是不透明的白纸。
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // 先铺白底：BufferedImage 的初始像素是全 0（黑），
            // 而很多 PPT 形状没有填充色，不铺底会把黑色透出来。
            g.setPaint(Color.WHITE);
            g.fill(new Rectangle2D.Double(0, 0, width, height));

            // 让 POI 按 PPT 自己的坐标（点）作画，由缩放负责放大到目标像素尺寸。
            // 这样同一个 SCALE 就能适配任意页面大小的课件。
            g.scale(SCALE, SCALE);

            // 必须在 draw 之前：这一步会改掉画不出中文的那些 run 的字体
            applyFontFallback(slide);

            slide.draw(g);
        } finally {
            // Graphics2D 持有原生绘图资源，不释放会泄漏
            g.dispose();
        }

        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("没有可用的 PNG 编码器");
        }
    }

    /**
     * 修掉「中文渲染成豆腐块（□□□）」。
     *
     * <h3>问题是什么</h3>
     * 很多课件把中文段落的**拉丁字体**设成了 {@code Arial} / {@code Times New Roman}
     * （作者在中文字体框里填了英文字体），东亚字体另存一处。而 POI 的
     * {@link XSLFTextRun#getFontFamily()} 取的是**拉丁**字体，{@code draw()} 时就把
     * AWT 的字体设成了 "Arial"。
     *
     * <p>关键在于：<b>AWT 用物理字体画不出的字符不会自动回退</b>——物理字体（Arial、
     * Times New Roman）没有字体链接表，缺字形就直接画成 □；只有 Java **逻辑字体**
     * （Dialog / SansSerif / Serif）才带回退。所以「平时成绩」变成「□□□□」，
     * 而同一页里用宋体的文字却完全正常。
     *
     * <h3>怎么修</h3>
     * 逐 run 检查：如果它当前指定的字体画不出这段文字，就换成 PPT 自己写的东亚字体；
     * 东亚字体也没有（或同样画不出）就用系统里能画中文的字体兜底。
     * 只改「确实画不出来」的 run，所以英文/数字仍然保持 Arial 的原样。
     *
     * <p>只处理**当前这一页**：pptx 是每页现开现关的，改完即弃，不会污染别页，
     * 也省掉遍历整个课件（百页课件会明显拖慢）。
     */
    private void applyFontFallback(XSLFSlide slide) {
        fixShapes(slide.getShapes());
    }

    private void fixShapes(List<XSLFShape> shapes) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape textShape) {
                // XSLFTableCell 也继承自 XSLFTextShape，所以表格单元格一并覆盖
                for (XSLFTextParagraph paragraph : textShape.getTextParagraphs()) {
                    for (XSLFTextRun run : paragraph.getTextRuns()) {
                        fixRun(run);
                    }
                }
            } else if (shape instanceof XSLFGroupShape group) {
                // 组合形状里的文字同样要处理，否则「成组的中文」还是豆腐块
                fixShapes(group.getShapes());
            }
        }
    }

    private void fixRun(XSLFTextRun run) {
        String text = run.getRawText();
        if (text == null || text.isBlank()) {
            return;
        }

        String family = run.getFontFamily();
        if (canDisplay(family, text)) {
            return;
        }

        // 1) 先用课件自己声明的东亚字体——那是作者对这段中文的真实意图
        String eastAsian = run.getFontFamily(FontGroup.EAST_ASIAN);
        String replacement = firstThatCanDisplay(List.of(eastAsian), text);

        // 2) 再退到系统里的中文字体
        if (replacement == null) {
            replacement = firstThatCanDisplay(CJK_FALLBACKS, text);
        }

        if (replacement == null) {
            // 所有候选都画不出：保持原样。至少不会比不改更差。
            log.warn("font_fallback_exhausted family={} text={}", family, abbreviate(text));
            return;
        }

        // 只改拉丁字体即可：出问题的正是 POI 拿它作画的那一个（见方法注释）
        run.setFontFamily(replacement, FontGroup.LATIN);
        log.debug("font_fallback family={} -> {} text={}", family, replacement, abbreviate(text));
    }

    /** 在候选里挑第一个能画出这段文字的字体名；都不行返回 null。 */
    private String firstThatCanDisplay(List<String> candidates, String text) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && canDisplay(candidate, text)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 字体名画不画得出这段文字。
     *
     * <p>字体名为空时返回 true：那表示「交给 AWT 默认字体」，而默认是逻辑字体，自带回退。
     * 字体名不存在时同理——{@code new Font()} 对未知名字会返回逻辑字体 Dialog，
     * 它也能画中文，所以不放行反而是错的。
     */
    private boolean canDisplay(String family, String text) {
        if (family == null || family.isBlank()) {
            return true;
        }
        Font font = FONT_CACHE.computeIfAbsent(family, name -> new Font(name, Font.PLAIN, 12));
        return font.canDisplayUpTo(text) < 0;
    }

    /** 日志里别把整段文字打出来，截一小段够定位即可。 */
    private String abbreviate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
