package com.fridayclass.service.agent.tool;

import com.fridayclass.dto.PageTextItem;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.KnowledgePointRepository;
import com.fridayclass.service.agent.AgentToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具 2：查当前所选课件的逐页文字与知识点。
 *
 * <p><b>参数里没有 coursewareId</b>：课件由服务端注入，理由同
 * {@link GetClassRecordsTool}。
 *
 * <h3>为什么页码参数要做上限</h3>
 *
 * 一份课件可以有一百多页，每页文字加知识点很容易上千字。
 * 全量塞进上下文不仅贵，还会把模型「淹没」——它在几万字里找不着重点，
 * 反而总结得比只看二十页还差。所以默认只给一段，并明确告诉模型还剩多少页。
 */
@Component
public class GetCoursewareContentTool implements AgentTool {

    /**
     * 一次最多返回多少页。
     *
     * <p>从 30 提到 60：整个任务只有 6 次工具调用，而 100 页的课件按 30 页一次要读 4 次，
     * 加上写文件与收尾就贴着上限了。<b>工具自己省着给，模型就会多用一步</b>——
     * 那一步比多返回几十页贵得多。
     */
    private static final int MAX_PAGES_PER_CALL = 60;
    /** 每页正文截断长度。 */
    private static final int MAX_PAGE_TEXT_CHARS = 1200;
    /** 每页最多带几条知识点。 */
    private static final int MAX_POINTS_PER_PAGE = 15;

    /**
     * 本次返回给模型的**总**字符上限。
     *
     * <p>每页的截断（1200 字）挡不住总量：60 页 × 1200 字就是 7 万多字，
     * 加上知识点更多。撑爆上下文窗口的后果是**下一轮请求被服务端判 400**，
     * 而这一轮的钱已经花掉了。所以总额必须单独卡。
     */
    private static final int MAX_OUTPUT_CHARS = 24_000;

    private final CoursewarePageRepository pageRepository;
    private final KnowledgePointRepository knowledgeRepository;

    public GetCoursewareContentTool(CoursewarePageRepository pageRepository,
                                    KnowledgePointRepository knowledgeRepository) {
        this.pageRepository = pageRepository;
        this.knowledgeRepository = knowledgeRepository;
    }

    @Override
    public String name() {
        return "get_courseware_content";
    }

    @Override
    public String description() {
        return """
                查询「当前所选课件」的逐页文字与已经提炼好的知识点。
                当老师要求整理课件、做复习资料、出练习题时，通常要先调用它拿到内容。
                **一次尽量多取几页**（如 pageFrom=1, pageTo=60），不要把同一份课件拆成很多次小范围查询——
                整个任务最多只有 6 次工具调用，拆着读会用光步数、文件就写不出来了。
                只能查到老师在界面上选定的那份课件，无法指定别的课件。
                如果老师没有选课件，这个工具不会被提供。""";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(java.util.Map.of(
                "keyword", ToolSchemas.string("可选。只返回文字或知识点里包含该关键词的页面。"),
                "pageFrom", ToolSchemas.integer("可选。起始页码（从 1 开始）。"),
                "pageTo", ToolSchemas.integer("可选。结束页码（含）。")), null);
    }

    @Override
    public boolean availableIn(AgentToolContext context) {
        return context.hasCourseware();
    }

    @Override
    public String execute(AgentToolContext context, Map<String, Object> args) {
        context.report("正在读取课件内容…");
        Long coursewareId = context.coursewareId();
        String keyword = ToolArgs.string(args, "keyword");

        List<CoursewarePage> allPages = pageRepository.findByCoursewareIdOrderByPageNoAsc(coursewareId);
        if (allPages.isEmpty()) {
            return "这份课件还没有解析出任何页面内容。";
        }

        int totalPages = allPages.size();
        int requestedFrom = defaulted(ToolArgs.positiveInteger(args, "pageFrom"), 1);
        int requestedTo = defaulted(ToolArgs.positiveInteger(args, "pageTo"),
                Math.min(requestedFrom + MAX_PAGES_PER_CALL - 1, totalPages));

        // 页码越界不算错误：模型可能算错，纠正后继续比报错让整轮失败更有用。
        //
        // ⚠ 这三个必须是 final：下面的 stream lambda 要捕获它们。
        //   先赋值再改写的话捕获不到，编译直接失败（写成 final 也顺带把
        //   「边界只算一次」这个意图钉死，后续改动不会漏掉某处修正）。
        final int from = Math.max(1, Math.min(requestedFrom, totalPages));
        final int capped = Math.max(from, Math.min(requestedTo, totalPages));

        // 防止模型一次要 100 页把上下文撑爆：超过上限就只给前面一段，并在结果里说明
        final boolean clipped = (capped - from + 1) > MAX_PAGES_PER_CALL;
        final int to = clipped ? from + MAX_PAGES_PER_CALL - 1 : capped;

        List<CoursewarePage> selected = allPages.stream()
                .filter(page -> page.getPageNo() != null
                        && page.getPageNo() >= from
                        && page.getPageNo() <= to)
                .toList();

        Map<Long, List<String>> pointsByPage = loadKnowledgePoints(selected);

        StringBuilder out = new StringBuilder();
        out.append("【课件内容】共 ").append(totalPages).append(" 页，本次返回第 ")
                .append(from).append('-').append(to).append(" 页");
        if (clipped) {
            out.append("（一次最多取 ").append(MAX_PAGES_PER_CALL).append(" 页，还有更多页可继续读取）");
        }
        out.append("：\n");

        int matched = 0;
        for (CoursewarePage page : selected) {
            List<String> points = pointsByPage.getOrDefault(page.getId(), List.of());
            String text = page.getTextContent();
            if (!matches(keyword, text, points)) {
                continue;
            }
            if (matched > 0 && out.length() > MAX_OUTPUT_CHARS) {
                out.append("\n（内容过多，以上到第 ").append(page.getPageNo()).append(" 页为止。")
                        .append("请用 pageFrom / pageTo 取后面的页码，或加 keyword 缩小范围）\n");
                return out.toString();
            }
            matched++;
            out.append("\n第 ").append(page.getPageNo()).append(" 页\n");
            if (text != null && !text.isBlank()) {
                out.append("文字：").append(clip(text, MAX_PAGE_TEXT_CHARS)).append('\n');
            }
            if (!points.isEmpty()) {
                out.append("知识点：\n");
                points.stream().limit(MAX_POINTS_PER_PAGE)
                        .forEach(point -> out.append("  · ").append(clip(point, 300)).append('\n'));
                if (points.size() > MAX_POINTS_PER_PAGE) {
                    out.append("  …（本页还有 ").append(points.size() - MAX_POINTS_PER_PAGE).append(" 条）\n");
                }
            }
        }

        if (matched == 0) {
            // keyword 为 null 时 matches() 恒真，所以"一条都没匹配上"只可能是
            // **这一段页码里根本没有页面**（比如页码越界、或课件是部分解析的）。
            // 那种情况下不能再拼 keyword —— 会打印出「没有包含「null」的内容」，
            // 模型会把这句原样转述给老师。
            if (keyword == null) {
                return "第 " + from + '-' + to + " 页里没有可读取的页面内容。"
                        + "这份课件共 " + totalPages + " 页，请换一个页码区间再试。";
            }
            return "第 " + from + '-' + to + " 页里没有包含「" + keyword + "」的内容。"
                    + "这份课件共 " + totalPages + " 页，可以换关键词或换页码区间再查。";
        }
        return out.toString();
    }

    /**
     * 一次取回这一批页的知识点 —— 用 {@code findByPageIds} 而不是逐页查，
     * 否则 30 页就是 30 次往返（N+1）。
     */
    private Map<Long, List<String>> loadKnowledgePoints(List<CoursewarePage> pages) {
        List<Long> pageIds = pages.stream().map(CoursewarePage::getId).toList();
        if (pageIds.isEmpty()) {
            return Map.of();
        }
        List<PageTextItem> items = knowledgeRepository.findByPageIds(pageIds);
        return items.stream().collect(java.util.stream.Collectors.groupingBy(
                PageTextItem::pageId,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.mapping(PageTextItem::content,
                        java.util.stream.Collectors.toList())));
    }

    private static boolean matches(String keyword, String text, List<String> points) {
        if (keyword == null) {
            return true;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return points.stream().anyMatch(p -> p != null && p.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static int defaulted(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("[ \\t]+", " ").strip();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
