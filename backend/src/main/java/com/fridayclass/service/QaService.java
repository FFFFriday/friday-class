package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.QaAskRequest;
import com.fridayclass.dto.QaRecordResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * F004 学生问答智能体：按当前页的知识点回答学生的问题。
 *
 * <h3>核心机制</h3>
 * 老师翻页 → 学生端切到该页上下文 → 提问时带 {@code pageId} → 后端<b>只取该页</b>知识点组提示词。
 * 上下文小、响应快、成本低，而且回答天然贴合老师正在讲的内容。
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
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final String MSG_PARSING = "课件还在解析中，稍后再试";
    private static final String MSG_PARSE_FAILED = "本页内容解析失败，请告诉老师";
    private static final String MSG_NO_CONTENT = "本页暂无解析内容，可先听老师讲解";
    private static final String MSG_LLM_FAILED = "AI 助教暂时忙不过来，请稍后再试";

    /** 每学生 1 问 / 5 秒（设计文档 §3.7）。防的是「一个班同时点发送」的瞬时并发。 */
    private static final long MIN_ASK_INTERVAL_MS = 5_000L;

    /** 相同问题的答案缓存时长（设计文档 §3.8）。课堂上「同一个问题多人问」极常见。 */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);

    /** 缓存条目上限。超过就整体清空——课堂上条目本来就少，粗略但绝不会无界增长。 */
    private static final int MAX_CACHE_ENTRIES = 2_000;

    /** 跟跑者等待领跑者的上限，取问答总预算，保证不会超出学生能接受的等待时间。 */
    private static final long FOLLOW_WAIT_SECONDS = 20L;

    private final ClassSessionRepository sessionRepository;
    private final CoursewarePageRepository pageRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final PresetQuestionRepository presetQuestionRepository;
    private final QaRecordRepository qaRecordRepository;
    private final UserRepository userRepository;
    private final DeepSeekClient llmClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 每学生上次提问时刻（毫秒）。
     *
     * <p>⚠️ <b>进程内状态</b>：没有 Redis（项目宪章硬约束），所以重启后端限流就清零，
     * 多实例部署时每个实例各算一份（等于放宽了 N 倍）。MVP 接受，但别把它当可靠限流。
     * 条目数等于学生数，不做清理。
     */
    private final Map<Long, Long> lastAskAtMs = new ConcurrentHashMap<>();

    /** 相同问题的答案缓存：key = 课堂|页|归一化问题。 */
    private final Map<String, CachedAnswer> answerCache = new ConcurrentHashMap<>();

    /**
     * 正在进行中的相同提问。
     *
     * <p>这是 <b>single-flight</b>：同一个问题被 20 个学生同时问，只有第一个人真的调模型，
     * 其余 19 个等他的结果。课堂上收益最大的一笔省钱（设计文档 §3.8）。
     */
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public QaService(ClassSessionRepository sessionRepository,
                     CoursewarePageRepository pageRepository,
                     KnowledgePointRepository knowledgePointRepository,
                     PresetQuestionRepository presetQuestionRepository,
                     QaRecordRepository qaRecordRepository,
                     UserRepository userRepository,
                     DeepSeekClient llmClient,
                     PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.pageRepository = pageRepository;
        this.knowledgePointRepository = knowledgePointRepository;
        this.presetQuestionRepository = presetQuestionRepository;
        this.qaRecordRepository = qaRecordRepository;
        this.userRepository = userRepository;
        this.llmClient = llmClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        // 3) 短事务：校验归属、判断该不该调模型
        Prepared prepared = transactionTemplate.execute(
                status -> prepare(request.sessionId(), request.pageId(), question));

        if (prepared.skipped()) {
            // 归还刚才扣掉的限流额度：这一问**根本没调模型**（解析中 / 解析失败 / 本页无内容），
            // 没有理由花掉学生那 5 秒。不还的话会出现自相矛盾的提示：
            // 学生先被告知「课件还在解析中」，两秒后再点又变成「提问太快了」——明明是同一件事。
            refundRateLimit(studentId);
            log.info("qa_skipped studentId={} sessionId={} pageId={} reason={}",
                    studentId, request.sessionId(), request.pageId(), prepared.skipMessage());
            return QaRecordResponse.skipped(request.pageId(), prepared.pageNo(),
                    request.question(), prepared.skipMessage());
        }

        // 4) 事务外调模型（可能走缓存 / 与别人的相同提问合并）
        AnswerOutcome outcome = answerWithDedup(request.sessionId(), request.pageId(), prepared);

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
     * 短事务里把「能不能答、拿什么答」一次性准备好。
     *
     * <p>放在一个事务里是有意义的：判断该不该答要看课件状态，
     * 而抓知识点是紧随其后的两步查询——分开做的话，
     * 中间可能正好赶上解析完成/失败，出现「状态说已解析、知识点却查不到」的错配。
     */
    private Prepared prepare(Long sessionId, Long pageId, String question) {
        ClassSession session = sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));

        if (session.getStatus() == SessionStatus.ENDED) {
            // 下课后 AI 拿着本页知识点答一道已经结束的课的问题，既没人看，
            // 也会在课后总结里混进噪声
            throw new BusinessException("课堂已结束，无法提问");
        }

        CoursewarePage page = pageRepository.findById(pageId)
                .orElseThrow(() -> new BusinessException(400, "页码与当前课堂不匹配"));

        Courseware courseware = session.getCourseware();
        if (courseware == null || page.getCourseware() == null
                || !courseware.getId().equals(page.getCourseware().getId())) {
            // 学生端如果传了别的课件的 pageId，会拿到完全不相关的知识点来回答，
            // 而且全程不报错——必须显式挡住
            throw new BusinessException(400, "页码与当前课堂不匹配");
        }

        CoursewareStatus status = courseware.getStatus();
        if (status == CoursewareStatus.UPLOADED
                || status == CoursewareStatus.CONVERTING
                || status == CoursewareStatus.PARSING) {
            return Prepared.skip(MSG_PARSING, page.getPageNo());
        }
        if (status == CoursewareStatus.FAILED) {
            return Prepared.skip(MSG_PARSE_FAILED, page.getPageNo());
        }

        List<KnowledgePoint> points = knowledgePointRepository.findByPageIdOrderBySortOrderAsc(pageId);
        List<PresetQuestion> questions = presetQuestionRepository.findByPageIdOrderBySortOrderAsc(pageId);

        if (points.isEmpty() && questions.isEmpty()) {
            return Prepared.skip(MSG_NO_CONTENT, page.getPageNo());
        }

        return Prepared.ready(page.getId(), page.getPageNo(),
                PromptTemplates.numbered(points.stream().map(KnowledgePoint::getContent).toList()),
                PromptTemplates.numbered(questions.stream().map(PresetQuestion::getContent).toList()),
                question);
    }

    /**
     * 调模型，带「相同问题去重缓存 + single-flight」。
     *
     * <p>自己失败和等人失败都<b>不抛异常</b>，而是返回 FAILED 结果——
     * 模型失败在设计里不是 HTTP 错误，而是「成功响应里带 status=FAILED」。
     */
    private AnswerOutcome answerWithDedup(Long sessionId, Long pageId, Prepared prepared) {
        String key = sessionId + "|" + pageId + "|" + prepared.question();

        CachedAnswer cached = answerCache.get(key);
        if (cached != null && !cached.expired()) {
            log.debug("qa_cache_hit key={}", key);
            return AnswerOutcome.of(cached.answer());
        }

        // single-flight：抢到的人负责调模型，抢不到的人等结果
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> leader = inFlight.putIfAbsent(key, mine);

        if (leader != null) {
            try {
                // 必须带超时：领跑者万一卡住，跟跑者不能跟着无限等，
                // 否则 Tomcat 线程会被一批相同提问全部占死
                return AnswerOutcome.of(leader.get(FOLLOW_WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (Exception ex) {
                log.warn("qa_follow_failed key={} reason={}", key, ex.toString());
                return AnswerOutcome.failed();
            }
        }

        try {
            LlmResult result = llmClient.chat(CallKind.QA,
                    PromptTemplates.qaSystemPrompt(),
                    PromptTemplates.qaUserPrompt(prepared.knowledgePoints(),
                            prepared.presetQuestions(), prepared.question()),
                    false);

            String answer = result.content().strip();
            mine.complete(answer);
            putCache(key, answer);
            return AnswerOutcome.of(answer);
        } catch (Exception ex) {
            log.warn("qa_llm_failed sessionId={} pageId={} reason={}",
                    sessionId, pageId, ex.toString());
            mine.completeExceptionally(ex);
            return AnswerOutcome.failed();
        } finally {
            // 不论成败都要移除，否则这个 key 永远卡在「有人在问」，后续提问全走超时分支
            inFlight.remove(key);
        }
    }

    /** 落库并组装响应。写入失败若是幂等键冲突，说明另一个并发请求已经写好了，直接把它的结果读回来。 */
    private QaRecordResponse persist(Long studentId, QaAskRequest request,
                                     Prepared prepared, AnswerOutcome outcome) {
        try {
            return transactionTemplate.execute(status -> {
                QaRecord record = new QaRecord();
                // 用 getReferenceById 拿代理即可，插外键不必先把关联对象查出来
                record.setSession(sessionRepository.getReferenceById(request.sessionId()));
                record.setStudent(userRepository.getReferenceById(studentId));
                record.setPage(pageRepository.getReferenceById(request.pageId()));
                record.setQuestion(request.question().strip());
                record.setAnswer(outcome.text());
                record.setStatus(outcome.status());
                record.setClientRequestId(blankToNull(request.clientRequestId()));
                record.setAskedAt(nowToSecond());

                QaRecord saved = qaRecordRepository.save(record);

                log.info("qa_answered studentId={} sessionId={} pageId={} status={} answerChars={}",
                        studentId, request.sessionId(), request.pageId(), outcome.status(),
                        outcome.text().length());

                // 直接用 prepared 里的 pageNo 组装，不再去读 saved.getPage()——
                // 那是个代理，读它又要多一次 SELECT
                return new QaRecordResponse(saved.getId(), prepared.pageId(), prepared.pageNo(),
                        saved.getQuestion(), saved.getAnswer(),
                        outcome.status().name(), saved.getAskedAt());
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

    private void putCache(String key, String answer) {
        if (answerCache.size() >= MAX_CACHE_ENTRIES) {
            // 粗略的兜底：课堂上同时有效的条目本来就少，整体清空比维护 LRU 划算得多
            answerCache.clear();
            log.debug("qa_cache_cleared");
        }
        answerCache.put(key, new CachedAnswer(answer, System.nanoTime() + DEDUP_TTL.toNanos()));
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

    /** 缓存条目。用 nanoTime 而不是 currentTimeMillis：后者会被系统时间调整影响。 */
    private record CachedAnswer(String answer, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }

    /** prepare 的结果：要么「该跳过，给这句提示」，要么「可以答，材料在这」。 */
    private record Prepared(String skipMessage, Long pageId, Integer pageNo,
                            String knowledgePoints, String presetQuestions, String question) {

        boolean skipped() {
            return skipMessage != null;
        }

        static Prepared skip(String message, Integer pageNo) {
            return new Prepared(message, null, pageNo, null, null, null);
        }

        static Prepared ready(Long pageId, Integer pageNo, String knowledgePoints,
                              String presetQuestions, String question) {
            return new Prepared(null, pageId, pageNo, knowledgePoints, presetQuestions, question);
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
