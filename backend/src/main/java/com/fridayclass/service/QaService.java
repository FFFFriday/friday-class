package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.QaAskRequest;
import com.fridayclass.dto.QaRecordResponse;
import com.fridayclass.entity.AiConversation;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.entity.KnowledgePoint;
import com.fridayclass.entity.PresetQuestion;
import com.fridayclass.entity.QaRecord;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.enums.QaStatus;
import com.fridayclass.enums.SessionStatus;
import com.fridayclass.llm.CallKind;
import com.fridayclass.llm.DeepSeekClient;
import com.fridayclass.llm.LlmResult;
import com.fridayclass.llm.PromptTemplates;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.KnowledgePointRepository;
import com.fridayclass.repository.PresetQuestionRepository;
import com.fridayclass.repository.QaRecordRepository;
import com.fridayclass.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F004 学生问答智能体：按当前页的知识点回答学生的问题，并带上会话上下文。
 *
 * <h3>核心机制</h3>
 * 老师翻页 → 学生端切到该页上下文 → 提问时带 {@code pageId} → 后端<b>只取该页</b>知识点组提示词。
 * 上下文小、响应快，而且回答天然贴合老师正在讲的内容。
 *
 * <h3>事务边界</h3>
 * 与解析服务同一条规矩：<b>模型调用在事务外</b>。一次调用要 1~8 秒，
 * 事务开着就等于占着数据库连接干等网络。所以本类拆成三段：
 * 短事务读数据 → 事务外调模型 → 短事务写结果。
 *
 * <h3>四种「没有答案」必须分开</h3>
 * 设计文档 §3.5 特别强调：V1 把「解析中 / 解析失败 / 本页没内容」统一说成
 * 「本页暂无解析内容」，会在课件还在解析时<b>误导学生</b>以为课件有问题。
 * 前三种<b>不调模型</b>（省一次冤枉钱），返回 {@code SKIPPED} 且<b>不落库</b>——
 * 落库会把「没问成」记成「问过了」，污染 F006 统计。
 *
 * <h3>⚠️ 「省钱两件套」已删除（2026-09-21）</h3>
 * 原先有两处优化：<b>答案复用缓存</b>（10 分钟内相同问题复用答案）与
 * <b>并发合流</b>（同一问题多人同时问只调一次模型）。两者都已移除，原因有二：
 * <ol>
 *   <li>按 Friday 指示<b>不省钱</b>；</li>
 *   <li>更要紧的是<b>它们会导致上下文串台</b>：缓存的键是
 *       「课堂|页|问题文字」，<b>根本不认识「会话」是什么</b>。
 *       A 会话问「什么是 TCP」得到答案 X（按 A 的上下文生成），
 *       10 分钟内 B 会话问同一句就会拿到 X —— B 看到的是别人上下文里的答案。</li>
 * </ol>
 * <b>保留</b>的是 {@code clientRequestId} + {@code uk_qa_client_req} 唯一索引的幂等：
 * 它不省模型钱，是防「手抖双击重复扣费」，无害且必要。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final String MSG_PARSING = "课件还在解析中，稍后再试";
    private static final String MSG_PARSE_FAILED = "本页内容解析失败，请告诉老师";
    private static final String MSG_NO_CONTENT = "本页暂无解析内容，可先听老师讲解";
    private static final String MSG_LLM_FAILED = "AI 助教暂时忙不过来，请稍后再试";
    private static final String MSG_TOO_LONG = "本会话太长了，建议新建一个会话继续";

    /** 每学生 1 问 / 5 秒（设计文档 §3.7）。防的是「一个班同时点发送」的瞬时并发。 */
    private static final long MIN_ASK_INTERVAL_MS = 5_000L;

    private final ClassSessionRepository sessionRepository;
    private final CoursewarePageRepository pageRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final PresetQuestionRepository presetQuestionRepository;
    private final QaRecordRepository qaRecordRepository;
    private final UserRepository userRepository;
    private final AiConversationService conversationService;
    private final DeepSeekClient llmClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 带进提示词的历史轮数（一条问答记录 = 一轮）。
     *
     * <p>这是<b>兜底</b>而不是优化：防止会话无限增长把模型上下文撑爆。
     * 按 Friday 指示不演示长对话，所以取一个保守值即可。
     */
    private final int contextTurns;

    /**
     * 每学生上次提问时刻（毫秒）。
     *
     * <p>⚠️ <b>进程内状态</b>：没有 Redis（项目宪章硬约束），所以重启后端限流就清零，
     * 多实例部署时每个实例各算一份（等于放宽了 N 倍）。MVP 接受，但别把它当可靠限流。
     * 条目数等于学生数，不做清理。
     */
    private final Map<Long, Long> lastAskAtMs = new ConcurrentHashMap<>();

    public QaService(ClassSessionRepository sessionRepository,
                     CoursewarePageRepository pageRepository,
                     KnowledgePointRepository knowledgePointRepository,
                     PresetQuestionRepository presetQuestionRepository,
                     QaRecordRepository qaRecordRepository,
                     UserRepository userRepository,
                     AiConversationService conversationService,
                     DeepSeekClient llmClient,
                     PlatformTransactionManager transactionManager,
                     @Value("${app.ai.context-turns:6}") int contextTurns) {
        this.sessionRepository = sessionRepository;
        this.pageRepository = pageRepository;
        this.knowledgePointRepository = knowledgePointRepository;
        this.presetQuestionRepository = presetQuestionRepository;
        this.qaRecordRepository = qaRecordRepository;
        this.userRepository = userRepository;
        this.conversationService = conversationService;
        this.llmClient = llmClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.contextTurns = Math.max(0, contextTurns);
    }

    /** 学生提问。 */
    public QaRecordResponse ask(Long studentId, QaAskRequest request) {
        // 1) 幂等：同一个 clientRequestId 已经答过就直接还回去，不重复调模型
        QaRecordResponse replay = replayIfDuplicate(request.clientRequestId());
        if (replay != null) {
            log.info("qa_replay studentId={} clientRequestId={}", studentId, request.clientRequestId());
            return replay;
        }

        // 2) 限流放在幂等之后：学生双击「发送」不该收到「问太快了」，
        //    那次重复本来就该被幂等地吃掉
        checkRateLimit(studentId);

        String question = PromptTemplates.normalizeQuestion(request.question());

        // 3) 短事务：解析会话、校验归属、判断该不该调模型、抓上下文
        Prepared prepared = transactionTemplate.execute(
                status -> prepare(studentId, request, question));

        if (prepared.skipped()) {
            // 归还刚才扣掉的限流额度：这一问**根本没调模型**（解析中 / 解析失败 / 本页无内容），
            // 没有理由花掉学生那 5 秒。不还的话会出现自相矛盾的提示：
            // 学生先被告知「课件还在解析中」，两秒后再点又变成「提问太快了」——明明是同一件事。
            refundRateLimit(studentId);
            log.info("qa_skipped studentId={} pageId={} reason={}",
                    studentId, request.pageId(), prepared.skipMessage());
            return QaRecordResponse.skipped(request.pageId(), prepared.pageNo(),
                    request.question(), prepared.skipMessage());
        }

        // 4) 事务外调模型（带会话上下文）
        AnswerOutcome outcome = answer(prepared);

        // 5) 短事务：落库并返回
        return persist(studentId, request, prepared, outcome);
    }

    /** 某课堂的问答记录。{@code pageId} 非空时只看那一页。 */
    @Transactional(readOnly = true)
    public List<QaRecordResponse> records(Long sessionId, Long pageId) {
        sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));

        List<QaRecord> records = (pageId == null)
                ? qaRecordRepository.findBySessionWithPage(sessionId)
                : qaRecordRepository.findBySessionAndPageWithPage(sessionId, pageId);

        // repository 里已 join fetch 了 page，所以事务内组装 DTO 不会触发懒加载
        return records.stream().map(QaRecordResponse::from).toList();
    }

    /**
     * 短事务里把「能不能答、用哪个会话、拿什么答、上文是什么」一次性准备好。
     *
     * <p>放在一个事务里是有意义的：判断该不该答要看课件状态，
     * 而抓知识点、解析会话是紧随其后的几步查询——分开做的话，
     * 中间可能正好赶上解析完成/失败，出现「状态说已解析、知识点却查不到」的错配。
     *
     * <p><b>顺序是有讲究的</b>：先做「要不要跳过」的判定，<b>再</b>解析会话。
     * 反过来的话，一次被跳过的提问也会顺手建出一个空会话，
     * 学生会看到列表里冒出一个自己没聊过的会话。
     */
    private Prepared prepare(Long studentId, QaAskRequest request, String question) {
        ClassSession session = null;
        Courseware courseware = null;
        CoursewarePage page = null;

        // ① 课堂（可空 = 课后提问）
        if (request.sessionId() != null) {
            session = sessionRepository.findDetailById(request.sessionId())
                    .orElseThrow(() -> new BusinessException(404, "课堂不存在"));
            if (session.getStatus() == SessionStatus.ENDED) {
                // 提前给一句死信：AI 拿着本页知识点答一道已经结束的课的问题，
                // 既没人看，也会在课后总结里混进噪声。
                // 课后想继续问，请走「AI 助手」页（那里不带 sessionId）。
                throw new BusinessException("课堂已结束。课后继续提问请到「AI 助手」页");
            }
            courseware = session.getCourseware();
        }

        // ② 页（可空 = 课后提问）
        if (request.pageId() != null) {
            page = pageRepository.findById(request.pageId())
                    .orElseThrow(() -> new BusinessException(400, "页码与当前课堂不匹配"));

            if (courseware != null && (page.getCourseware() == null
                    || !courseware.getId().equals(page.getCourseware().getId()))) {
                // 学生端如果传了别的课件的 pageId，会拿到完全不相关的知识点来回答，
                // 而且全程不报错——必须显式挡住
                throw new BusinessException(400, "页码与当前课堂不匹配");
            }
            if (courseware == null) {
                // 只给了 pageId 没给 sessionId：从页反推课件，题目仍然有据可依
                courseware = page.getCourseware();
            }
        }

        // ③ 该不该答（只有在「基于某一页」提问时才有意义）
        String knowledgePoints = "";
        String presetQuestions = "";
        if (page != null) {
            CoursewareStatus status = courseware == null ? null : courseware.getStatus();
            if (status == CoursewareStatus.UPLOADED
                    || status == CoursewareStatus.CONVERTING
                    || status == CoursewareStatus.PARSING) {
                return Prepared.skip(MSG_PARSING, page.getPageNo());
            }
            if (status == CoursewareStatus.FAILED) {
                return Prepared.skip(MSG_PARSE_FAILED, page.getPageNo());
            }

            List<KnowledgePoint> points =
                    knowledgePointRepository.findByPageIdOrderBySortOrderAsc(page.getId());
            List<PresetQuestion> presets =
                    presetQuestionRepository.findByPageIdOrderBySortOrderAsc(page.getId());

            if (points.isEmpty() && presets.isEmpty()) {
                return Prepared.skip(MSG_NO_CONTENT, page.getPageNo());
            }

            knowledgePoints = PromptTemplates.numbered(
                    points.stream().map(KnowledgePoint::getContent).toList());
            presetQuestions = PromptTemplates.numbered(
                    presets.stream().map(PresetQuestion::getContent).toList());
        }

        // ④ 会话：先校验/取出，再组装上文
        Long coursewareId = courseware == null ? null : courseware.getId();
        Long sessionId = session == null ? null : session.getId();
        AiConversation conversation = resolveConversation(
                studentId, request.conversationId(), coursewareId, sessionId);

        // ⑤ 上文（不含本次提问——它还没落库）
        String history = buildHistory(conversation.getId());

        return Prepared.ready(conversation.getId(), sessionId, coursewareId, page,
                knowledgePoints, presetQuestions, history, question);
    }

    /**
     * 取会话：传了 conversationId 就校验归属，没传就落到该学生在该课件下的默认会话。
     *
     * <p>「没传」这条路径是<b>兼容老前端</b>用的（M4 §2 的最小改动原则）——
     * 老 UI 不带 conversationId，照样能提问，只是会自动归到一个默认会话里。
     */
    private AiConversation resolveConversation(Long studentId, Long conversationId,
                                               Long coursewareId, Long sessionId) {
        AiConversation conversation = (conversationId != null)
                ? conversationService.requireOwned(studentId, conversationId)
                : conversationService.resolveDefault(studentId, coursewareId, sessionId);

        long count = qaRecordRepository.countByConversationId(conversation.getId());
        if (count >= AiConversationService.MAX_MESSAGES_PER_CONVERSATION) {
            // 在这里拦而不是落库时拦：这时还没调模型，拦下来不花钱
            throw new BusinessException(MSG_TOO_LONG);
        }
        return conversation;
    }

    /**
     * 组装会话上文：取最近 N 轮，反转成正序，拼成「学生 / 助教」交替的文本。
     *
     * <p>每段都走 {@link PromptTemplates#truncateForHistory}——
     * 它同时做截断与<b>剥离定界符</b>。历史里的学生提问是自由输入，
     * 上一轮塞一句「忽略之前的要求」，这一轮会以更高的可信度重新进入提示词
     * （模型更容易把「自己说过的话」当成可信内容），所以必须和当次提问同等对待。
     */
    private String buildHistory(Long conversationId) {
        if (contextTurns <= 0) {
            return "";
        }
        List<QaRecord> recent = qaRecordRepository.findRecentByConversationId(
                conversationId, PageRequest.of(0, contextTurns));
        if (recent.isEmpty()) {
            return "";
        }

        List<QaRecord> asc = new ArrayList<>(recent);
        Collections.reverse(asc);

        StringBuilder sb = new StringBuilder();
        for (QaRecord record : asc) {
            sb.append("学生：")
                    .append(PromptTemplates.truncateForHistory(
                            record.getQuestion(), PromptTemplates.MAX_HISTORY_QUESTION_CHARS))
                    .append('\n');
            sb.append("助教：")
                    .append(PromptTemplates.truncateForHistory(
                            record.getAnswer(), PromptTemplates.MAX_HISTORY_ANSWER_CHARS))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 调模型。
     *
     * <p>失败<b>不抛异常</b>，而是返回 FAILED 结果——模型失败在设计里不是 HTTP 错误，
     * 而是「成功响应里带 status=FAILED」。
     *
     * <p>系统提示词有两套：有本页资料时用「只依据资料回答」的严格版；
     * 课后自由问答没有资料，必须换通用版——否则学生问什么都会得到
     * 「这部分课件没有提到」，等于这个功能不存在。
     */
    private AnswerOutcome answer(Prepared prepared) {
        boolean hasPageMaterial = !prepared.knowledgePoints().isBlank()
                || !prepared.presetQuestions().isBlank();

        String systemPrompt = hasPageMaterial
                ? PromptTemplates.qaSystemPrompt()
                : PromptTemplates.qaFreeSystemPrompt();

        try {
            LlmResult result = llmClient.chat(CallKind.QA, systemPrompt,
                    PromptTemplates.qaUserPrompt(prepared.knowledgePoints(),
                            prepared.presetQuestions(), prepared.history(), prepared.question()),
                    false);
            return AnswerOutcome.of(result.content().strip());
        } catch (Exception ex) {
            log.warn("qa_llm_failed conversationId={} reason={}",
                    prepared.conversationId(), ex.toString());
            return AnswerOutcome.failed();
        }
    }

    /**
     * 落库并组装响应。
     *
     * <p><b>响应必须在事务内组装</b>：{@code QaRecordResponse.from} 要读 {@code page.pageNo}，
     * 而 page 是 {@code getReferenceById} 拿到的懒代理；事务一关再读就抛
     * {@code LazyInitializationException}。（讨论区那边踩过同一个坑，
     * 症状是「消息进了库但全班收不到广播」。）
     */
    private QaRecordResponse persist(Long studentId, QaAskRequest request,
                                     Prepared prepared, AnswerOutcome outcome) {
        try {
            return transactionTemplate.execute(status -> {
                // 轮次要在 insert **之前**数：本条就是第 (现有条数 + 1) 轮
                long existing = qaRecordRepository.countByConversationId(prepared.conversationId());
                int turn = (int) existing + 1;

                QaRecord record = new QaRecord();
                // 课后提问没有课堂、没有页——这两个关联都可空了（见 01_数据库建模 §3.2）
                if (prepared.sessionId() != null) {
                    record.setSession(sessionRepository.getReferenceById(prepared.sessionId()));
                } else {
                    record.setSession(null);
                }
                record.setStudent(userRepository.getReferenceById(studentId));
                record.setPage(prepared.pageId() == null
                        ? null : pageRepository.getReferenceById(prepared.pageId()));
                record.setConversationId(prepared.conversationId());
                record.setCoursewareId(prepared.coursewareId());
                record.setQuestion(request.question().strip());
                record.setAnswer(outcome.text());
                record.setStatus(outcome.status());
                record.setClientRequestId(blankToNull(request.clientRequestId()));
                record.setAskedAt(nowToSecond());

                QaRecord saved = qaRecordRepository.save(record);

                // 刷新会话活跃时间。列表按它倒序，不刷新的话刚聊过的会话会被挤下去。
                conversationService.touch(prepared.conversationId());

                log.info("qa_answered studentId={} conversationId={} turn={} sessionId={} pageId={} status={} answerChars={}",
                        studentId, prepared.conversationId(), turn, prepared.sessionId(),
                        prepared.pageId(), outcome.status(), outcome.text().length());

                return QaRecordResponse.from(saved, turn);
            });
        } catch (DataIntegrityViolationException ex) {
            // 撞上 uk_qa_client_req：另一个并发请求已经用同一个幂等键写进去了
            QaRecordResponse existing = replayIfDuplicate(request.clientRequestId());
            if (existing != null) {
                log.info("qa_idempotent_race clientRequestId={}", request.clientRequestId());
                return existing;
            }
            throw ex;
        }
    }

    /** 幂等重放。没有幂等键、或查不到记录时返回 null。 */
    private QaRecordResponse replayIfDuplicate(String clientRequestId) {
        String key = blankToNull(clientRequestId);
        if (key == null) {
            return null;
        }
        // 用事务包一下：QaRecordResponse.from 要读 page.pageNo，而那是懒加载关联，
        // 事务外读会抛 LazyInitializationException
        return transactionTemplate.execute(status ->
                qaRecordRepository.findByClientRequestId(key)
                        .map(QaRecordResponse::from)
                        .orElse(null));
    }

    /**
     * 每学生限流。
     *
     * <p>「先读后写」在这里<b>不需要</b>加锁：最坏情况是并发两次提问都通过了检查，
     * 多问了一个而已，不影响正确性。为它上锁反而得不偿失。
     */
    private void checkRateLimit(Long studentId) {
        long now = System.currentTimeMillis();
        Long last = lastAskAtMs.get(studentId);
        if (last != null && now - last < MIN_ASK_INTERVAL_MS) {
            throw new BusinessException(429, "提问太快了，请稍等几秒再问");
        }
        lastAskAtMs.put(studentId, now);
    }

    /**
     * 归还限流额度（用于「这一问没调模型」的跳过路径）。
     *
     * <p>限流检查刻意放在 {@link #prepare} <b>之前</b>——它同时也在保护 prepare 里的几次查询。
     * 但 prepare 可能判定「这题不用问」，那种情况一次模型调用都没发生，
     * 扣掉 5 秒就纯属误伤（见 {@link #ask} 里调用处的注释）。
     *
     * <p>用 {@code remove} 而不是回填旧时间戳：语义是「回到从没问过」。
     * 并发下可能把另一个请求刚写入的时间戳一并抹掉（等于放宽一次），
     * 但这只发生在不花钱的路径上，可以接受。
     */
    private void refundRateLimit(Long studentId) {
        lastAskAtMs.remove(studentId);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 与 ClassSessionService 同一条规矩：DATETIME 只有秒精度，先截断再存。 */
    private static LocalDateTime nowToSecond() {
        return LocalDateTime.now().withNano(0);
    }

    /** prepare 的结果：要么「该跳过，给这句提示」，要么「可以答，材料在这」。 */
    private record Prepared(String skipMessage, Long conversationId, Long sessionId, Long coursewareId,
                            Long pageId, Integer pageNo, String knowledgePoints,
                            String presetQuestions, String history, String question) {

        boolean skipped() {
            return skipMessage != null;
        }

        static Prepared skip(String message, Integer pageNo) {
            return new Prepared(message, null, null, null, null, pageNo, null, null, null, null);
        }

        static Prepared ready(Long conversationId, Long sessionId, Long coursewareId,
                              CoursewarePage page, String knowledgePoints, String presetQuestions,
                              String history, String question) {
            return new Prepared(null, conversationId, sessionId, coursewareId,
                    page == null ? null : page.getId(), page == null ? null : page.getPageNo(),
                    knowledgePoints == null ? "" : knowledgePoints,
                    presetQuestions == null ? "" : presetQuestions,
                    history == null ? "" : history, question);
        }
    }

    /** 一次问答的结果：文本 + 状态。失败也走这里，而不是抛异常。 */
    private record AnswerOutcome(String text, QaStatus status) {

        static AnswerOutcome of(String answer) {
            return new AnswerOutcome(answer, QaStatus.SUCCESS);
        }

        static AnswerOutcome failed() {
            return new AnswerOutcome(MSG_LLM_FAILED, QaStatus.FAILED);
        }
    }
}
