package com.fridayclass.service.agent.tool;

import com.fridayclass.entity.ChatMessage;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.QaRecord;
import com.fridayclass.enums.ChatMessageStatus;
import com.fridayclass.enums.QaStatus;
import com.fridayclass.repository.ChatMessageRepository;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.QaRecordRepository;
import com.fridayclass.service.agent.AgentToolContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具 1：查当前所选课堂的讨论区发言与学生提问。
 *
 * <p><b>参数里没有 sessionId</b>：课堂由服务端从 {@link AgentToolContext} 注入。
 * 模型能表达的只有「要不要按关键词过滤」。
 */
@Component
public class GetClassRecordsTool implements AgentTool {

    /** 单次取回的最大发言条数。够覆盖一节课，又不至于把上下文撑爆。 */
    private static final int MAX_MESSAGES = 200;
    /** 单次取回的最大问答条数。 */
    private static final int MAX_QA = 100;
    /** 单条内容截断长度：个别学生可能粘一大段代码进来。 */
    private static final int MAX_ITEM_CHARS = 300;

    /**
     * 本次返回给模型的**总**字符上限。
     *
     * <h3>为什么单条有上限还不够</h3>
     *
     * 200 条 ×300 字 + 100 条问答 ×600 字 ≈ 12 万字。中文按 1~2 字一个 token 算，
     * 这一坨就是十几万 token —— 直接超过模型的上下文窗口。
     * 后果不是「答得差一点」，而是**下一轮请求被服务端判 400 直接失败**：
     * 钱已经花在第一轮上了，任务却拿不到任何结果。
     *
     * <p>所以总额也要卡死。超出时明说「还有更多」，让模型知道可以带 keyword 再查 ——
     * 比丢给它一坨超长文本然后整轮崩掉好得多。
     */
    private static final int MAX_OUTPUT_CHARS = 24_000;

    private final ClassSessionRepository sessionRepository;
    private final ChatMessageRepository chatRepository;
    private final QaRecordRepository qaRepository;

    public GetClassRecordsTool(ClassSessionRepository sessionRepository,
                               ChatMessageRepository chatRepository,
                               QaRecordRepository qaRepository) {
        this.sessionRepository = sessionRepository;
        this.chatRepository = chatRepository;
        this.qaRepository = qaRepository;
    }

    @Override
    public String name() {
        return "get_class_records";
    }

    @Override
    public String description() {
        return """
                查询「当前所选课堂」的讨论区发言与学生向 AI 提的问题。
                当老师要求汇总学生提问、整理课堂讨论、回顾这节课的情况时使用。
                只能查到老师在界面上选定的那一节课，无法指定别的课堂。
                如果老师没有选课堂，这个工具不会被提供。""";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of(
                "keyword", ToolSchemas.string("可选。只返回内容里包含该关键词的记录；不填则全部返回。")), null);
    }

    @Override
    public boolean availableIn(AgentToolContext context) {
        return context.hasSession();
    }

    @Override
    public String execute(AgentToolContext context, Map<String, Object> args) {
        context.report("正在查询课堂记录…");
        Long sessionId = context.sessionId();
        String keyword = ToolArgs.string(args, "keyword");

        StringBuilder out = new StringBuilder();
        appendSessionTitle(out, sessionId);
        appendChatMessages(out, sessionId, keyword);
        appendQaRecords(out, sessionId, keyword);

        if (out.length() == 0) {
            return "这节课还没有任何讨论区发言或提问记录。";
        }
        return out.toString();
    }

    private void appendSessionTitle(StringBuilder out, Long sessionId) {
        // 只读 title 这个普通列，不碰任何懒加载关联 —— 这里没有事务，
        // 碰关联对象会抛 LazyInitializationException。
        sessionRepository.findById(sessionId)
                .map(ClassSession::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .ifPresent(title -> out.append("【课堂】").append(title).append('\n'));
    }

    private void appendChatMessages(StringBuilder out, Long sessionId, String keyword) {
        List<ChatMessage> messages = chatRepository.findWithUserBySession(
                sessionId, PageRequest.of(0, MAX_MESSAGES));

        List<ChatMessage> kept = messages.stream()
                // 撤回的不算：它们已经不该再被看到，让 AI 总结进去等于把撤回操作抹掉了
                .filter(m -> m.getStatus() == ChatMessageStatus.NORMAL)
                .filter(m -> matches(keyword, m.getContent()))
                .toList();

        out.append("\n【讨论区发言】");
        if (keyword != null) {
            out.append("（关键词「").append(keyword).append("」）");
        }
        if (kept.isEmpty()) {
            out.append("\n（没有符合条件的发言）\n");
            return;
        }
        out.append("共 ").append(kept.size()).append(" 条：\n");
        int written = 0;
        for (ChatMessage message : kept) {
            if (written > 0 && out.length() > MAX_OUTPUT_CHARS) {
                out.append("（内容过多，以上为较早的 ").append(written).append(" 条。")
                        .append("可以带 keyword 再查一次，只取相关的部分）\n");
                return;
            }
            out.append("- ").append(nicknameOf(message)).append("：")
                    .append(clip(message.getContent())).append('\n');
            written++;
        }
        if (messages.size() == MAX_MESSAGES) {
            out.append("（发言较多，以上为最早的 ").append(MAX_MESSAGES).append(" 条）\n");
        }
    }

    /**
     * 取显示名。
     *
     * <p>昵称是<b>可空</b>的（注册时选填）。{@code StringBuilder.append(null)}
     * 会追加字面量 "null"，而模型被要求「忠于材料」—— 它会把 "null" 原样抄进
     * 交给老师的文档里。所以这里必须兜底，和上面讨论区那段同一个口径。
     */
    private static String nicknameOf(ChatMessage message) {
        if (message.getUser() == null) {
            return "（已注销用户）";
        }
        String nickname = message.getUser().getNickname();
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        String username = message.getUser().getUsername();
        return (username == null || username.isBlank()) ? "（未命名用户）" : username;
    }

    private void appendQaRecords(StringBuilder out, Long sessionId, String keyword) {
        // 用带 Pageable 的重载：截断要发生在**数据库那一层**。
        // 拿全量再在 Java 里 limit 的话，一节课几百条问答会连带 student/page
        // 一起被拉进内存，白白占堆。
        //
        // 多取一些（MAX_QA × 3）再在内存里按 keyword 过滤：状态过滤与关键词过滤
        // 都得在 Java 里做，取太少会在过滤后不够 MAX_QA 条。
        List<QaRecord> records = qaRepository.findBySessionWithStudentAndPage(
                sessionId, PageRequest.of(0, MAX_QA * 3));

        List<QaRecord> kept = records.stream()
                .filter(r -> r.getStatus() == QaStatus.SUCCESS)
                .filter(r -> matches(keyword, r.getQuestion()) || matches(keyword, r.getAnswer()))
                .limit(MAX_QA)
                .toList();

        out.append("\n【学生向 AI 提的问题】");
        if (keyword != null) {
            out.append("（关键词「").append(keyword).append("」）");
        }
        if (kept.isEmpty()) {
            out.append("\n（没有符合条件的提问）\n");
            return;
        }
        out.append("共 ").append(kept.size()).append(" 条：\n");
        int written = 0;
        for (QaRecord record : kept) {
            if (written > 0 && out.length() > MAX_OUTPUT_CHARS) {
                out.append("（内容过多，以上为较早的 ").append(written).append(" 条。")
                        .append("可以带 keyword 再查一次）\n");
                return;
            }
            // 昵称可空，兜底口径与讨论区那段一致（见 nicknameOf）
            String nickname = record.getStudent() == null ? "（已注销用户）" : record.getStudent().getNickname();
            if (nickname == null || nickname.isBlank()) {
                nickname = record.getStudent() == null ? "（已注销用户）"
                        : (record.getStudent().getUsername() == null ? "（未命名用户）"
                                : record.getStudent().getUsername());
            }
            out.append("- 问（").append(nickname).append("）：").append(clip(record.getQuestion())).append('\n');
            if (record.getAnswer() != null && !record.getAnswer().isBlank()) {
                out.append("  答：").append(clip(record.getAnswer())).append('\n');
            }
            written++;
        }
        if (records.size() > MAX_QA) {
            out.append("（提问较多，以上为前 ").append(MAX_QA).append(" 条）\n");
        }
    }

    /** 关键词为 null 时全部通过；不区分大小写。 */
    private static boolean matches(String keyword, String text) {
        if (keyword == null) {
            return true;
        }
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_ITEM_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_ITEM_CHARS) + "…";
    }
}
