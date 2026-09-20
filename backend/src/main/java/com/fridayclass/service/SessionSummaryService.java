package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.SessionSummaryResponse;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.CourseSummary;
import com.fridayclass.enums.SummaryStatus;
import com.fridayclass.llm.CallKind;
import com.fridayclass.llm.DeepSeekClient;
import com.fridayclass.llm.LlmResult;
import com.fridayclass.llm.PromptTemplates;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CourseSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 课堂总结（M5）。
 *
 * <h3>为什么是异步</h3>
 * 一次总结要几十秒（取决记录长度）。同步做的话接口会挂在那里，
 * 前端只能转圈，而且中途断开就整篇白跑。所以：<b>先落一条 PENDING 立刻返回，
 * 后台跑，前端轮询状态</b>。这与 {@code AiParseService} 是同一套模式。
 *
 * <h3>总结只基于聊天记录</h3>
 * 输入<b>只有</b>讨论区发言与学生问答，<b>不含课件、不含知识点</b>——需求 4 明确限定。
 * 一旦把课件资料也喂进去，总结就会开始摘录课件内容，
 * 而那部分学生并没有真的讨论过，老师看了会以为「这节讲得很好」。
 *
 * <h3>三道省钱闸门（在调模型之前拦下）</h3>
 * <ol>
 *   <li>权限：不是本课堂教师/管理员 → 403，不花钱；</li>
 *   <li>没记录 → {@code BIZ_SUMMARY_EMPTY}，不花钱；</li>
 *   <li>正在生成中 → {@code BIZ_SUMMARY_RUNNING}，不重复花钱。</li>
 * </ol>
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    /** 截断时写在正文开头。不单开字段，理由见 {@link SessionSummaryResponse}。 */
    private static final String TRUNCATION_NOTE = "（注：本堂课记录过长，以下总结基于截断后的内容）\n\n";

    private static final String MSG_EMPTY = "本堂课没有任何讨论或提问记录，无法生成总结";
    private static final String MSG_RUNNING = "总结正在生成中，请稍候再试";

    private final SessionRecordService recordService;
    private final CourseSummaryRepository summaryRepository;
    private final ClassSessionRepository sessionRepository;
    private final DeepSeekClient llmClient;
    private final ThreadPoolTaskExecutor summaryExecutor;
    private final TransactionTemplate transactionTemplate;

    /**
     * 正在生成中的课堂。<b>判重靠它，不靠数据库里的状态字段。</b>
     *
     * <h3>为什么不用 status 判</h3>
     * 一开始写的是「status == RUNNING 就拒绝」，但有两个洞：
     * <ol>
     *   <li><b>中间有个窗口</b>：状态先落 PENDING，后台线程排到之后才改成 RUNNING。
     *       老师在这几十毫秒里再点一次，就重复提交了一次模型调用——正是要防的那件事；</li>
     *   <li>更糟的是<b>会把人永久锁死</b>：后端重启、或任务异常终止时，
     *       库里留下一条永远是 PENDING 的记录。只看状态的话，
     *       这堂课从此再也生成不了总结，而且没有任何界面能让他恢复。</li>
     * </ol>
     *
     * <p>进程内集合正好对上：正常运行期它最准确（任务一结束就移除），
     * 进程重启后它自动清空（那条卡住的 PENDING 也就放行了）。
     *
     * <p>⚠️ 多实例部署时每个实例各有一份，等于放宽——与限流是同一类取舍，
     * 本项目单机运行，够用。
     */
    private final java.util.Set<Long> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SessionSummaryService(SessionRecordService recordService,
                                 CourseSummaryRepository summaryRepository,
                                 ClassSessionRepository sessionRepository,
                                 DeepSeekClient llmClient,
                                 @Qualifier("summaryExecutor") ThreadPoolTaskExecutor summaryExecutor,
                                 PlatformTransactionManager transactionManager) {
        this.recordService = recordService;
        this.summaryRepository = summaryRepository;
        this.sessionRepository = sessionRepository;
        this.llmClient = llmClient;
        this.summaryExecutor = summaryExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 触发生成。立即返回（状态 PENDING），真正的模型调用在后台跑。
     *
     * <p>与解析服务同一条规矩：<b>先落库、事务提交之后再提交后台任务</b>。
     * 反过来的话，后台线程可能在 PENDING 还没提交时就跑起来、查不到那条记录，
     * 于是状态更新无处可写，前端会一直看到「生成中」。
     */
    public SessionSummaryResponse generate(Long sessionId, Long userId, String role) {
        // 判重放在最前面，且用**原子**的 add：并发两次点击只有一个能通过。
        // 放在权限校验之前是有意的——重复提交是纯粹的浪费，没必要先查一次库再拒绝。
        if (!running.add(sessionId)) {
            throw new BusinessException(MSG_RUNNING);
        }

        SessionRecordService.SessionTexts texts;
        try {
            texts = transactionTemplate.execute(status -> {
                ClassSession session = recordService.requireAccess(sessionId, userId, role);

                SessionRecordService.SessionTexts collected = recordService.collectTexts(sessionId);
                if (collected.chatText().isBlank() && collected.qaText().isBlank()) {
                    // 没有任何记录就调模型，等于花钱让它编一篇——必须在这里拦住
                    throw new BusinessException(MSG_EMPTY);
                }

                CourseSummary summary = summaryRepository.findBySessionId(sessionId).orElse(null);
                if (summary == null) {
                    summary = new CourseSummary();
                    summary.setSession(sessionRepository.getReferenceById(sessionId));
                    if (session.getCourseware() == null) {
                        // course_summary.courseware_id 是 NOT NULL，缺了它插不进去
                        throw new BusinessException("这份课件已被删除，无法生成课堂总结");
                    }
                    summary.setCourseware(session.getCourseware());
                }
                // 重新生成：清掉上一次的正文与错误，状态回到 PENDING
                summary.setStatus(SummaryStatus.PENDING);
                summary.setContent(null);
                summary.setErrorMessage(null);
                summary.setSource(SOURCE_SESSION_CHAT);
                summaryRepository.save(summary);

                return collected;
            });
        } catch (RuntimeException ex) {
            // 上面任何一步失败（没权限 / 没记录 / 课件被删）都要把判重标记还回去。
            // 漏了这一步，这堂课就被永久锁死——之后再点永远得到「正在生成中」。
            running.remove(sessionId);
            throw ex;
        }

        try {
            summaryExecutor.execute(() -> runGeneration(sessionId, texts));
        } catch (RuntimeException ex) {
            // 线程池拒绝（只会在关停时发生）：标记还回去，并把状态落成 FAILED，
            // 否则前端会一直停在 PENDING 上转圈
            running.remove(sessionId);
            markStatus(sessionId, SummaryStatus.FAILED, null, "提交生成任务失败，请稍后重试");
            throw new BusinessException("提交生成任务失败，请稍后重试");
        }

        log.info("summary_submitted sessionId={} by={}", sessionId, userId);
        return SessionSummaryResponse.pending(SummaryStatus.PENDING);
    }

    /** 读取总结（含状态）。没生成过时 {@code status} 为 null。 */
    public SessionSummaryResponse get(Long sessionId, Long userId, String role) {
        recordService.requireAccess(sessionId, userId, role);
        return transactionTemplate.execute(status ->
                summaryRepository.findBySessionId(sessionId)
                        .map(SessionSummaryResponse::of)
                        .orElseGet(SessionSummaryResponse::none));
    }

    // ── 后台任务 ───────────────────────────────────────────────

    /**
     * 在总结线程池里跑的那一段。<b>全程不持有事务</b>：
     * 模型调用要几十秒，事务开着等于占着数据库连接干等网络。
     * 只用一个短事务翻状态。
     */
    private void runGeneration(Long sessionId, SessionRecordService.SessionTexts texts) {
        try {
            markStatus(sessionId, SummaryStatus.RUNNING, null, null);

            String source = buildSource(texts);
            boolean truncated = source.codePointCount(0, source.length())
                    > PromptTemplates.MAX_SUMMARY_SOURCE_CHARS;
            if (truncated) {
                source = truncateToCodePoints(source, PromptTemplates.MAX_SUMMARY_SOURCE_CHARS);
            }

            LlmResult result = llmClient.chat(CallKind.SUMMARY,
                    PromptTemplates.sessionSummarySystemPrompt(),
                    PromptTemplates.sessionSummary(source, truncated),
                    false);

            String content = result.content().strip();
            if (truncated) {
                content = TRUNCATION_NOTE + content;
            }
            markStatus(sessionId, SummaryStatus.SUCCESS, content, null);
            log.info("summary_succeeded sessionId={} chars={} truncated={}",
                    sessionId, content.length(), truncated);

        } catch (Exception ex) {
            // 失败要落库：只打日志的话，前端会永远停在「生成中」，
            // 而老师完全不知道发生了什么（界面上就是个转不完的圈）。
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            markStatus(sessionId, SummaryStatus.FAILED, null, truncate(message, 500));
            log.warn("summary_failed sessionId={} reason={}", sessionId, message);
        } finally {
            // 无论成败都要把判重标记摘掉，否则这堂课再也生成不了总结
            running.remove(sessionId);
        }
    }

    /** 拼给模型的原文。两段都空的时候不会走到这里（generate 已拦）。 */
    private String buildSource(SessionRecordService.SessionTexts texts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【课堂讨论区发言】\n");
        sb.append(texts.chatText().isBlank() ? "（本堂课没有讨论区发言）\n" : texts.chatText());
        sb.append("\n【学生与 AI 助手的问答】\n");
        sb.append(texts.qaText().isBlank() ? "（本堂课没有 AI 问答）" : texts.qaText());
        return sb.toString();
    }

    private void markStatus(Long sessionId, SummaryStatus status, String content, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx ->
                summaryRepository.findBySessionId(sessionId).ifPresent(summary -> {
                    summary.setStatus(status);
                    if (content != null) {
                        summary.setContent(content);
                    }
                    summary.setErrorMessage(errorMessage);
                    summaryRepository.save(summary);
                }));
    }

    /** 按码点截断，避免把 emoji 等代理对切成半个字符。 */
    private static String truncateToCodePoints(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    private static String truncate(String text, int maxChars) {
        return truncateToCodePoints(text, maxChars);
    }

    /** 总结来源标记。本次只做这一种；将来若要全量总结再补第二个值。 */
    private static final String SOURCE_SESSION_CHAT = "SESSION_CHAT";
}
