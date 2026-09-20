package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.AiConversationResponse;
import com.fridayclass.dto.ConversationDetailResponse;
import com.fridayclass.dto.CreateConversationRequest;
import com.fridayclass.dto.QaRecordResponse;
import com.fridayclass.entity.AiConversation;
import com.fridayclass.repository.AiConversationRepository;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.ConversationCount;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.QaRecordRepository;
import com.fridayclass.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学生 AI 会话的业务（M4）。
 *
 * <h3>归属模型</h3>
 * 会话归<b>学生本人</b>（{@code ai_conversation.student_id}），学生可建多个、可切换、可跨课延续。
 * 每条问答上另外打「课堂 ID + 课件 ID + 页码」三枚标签——于是
 * 「课堂记录」= 按课堂过滤、「学生档案」= 按学生过滤，<b>同一批数据两个视角看，不冲突</b>。
 *
 * <h3>会话之间上下文完全隔离</h3>
 * 互不引用、互不污染。{@code courseware} / {@code session} 只是「创建时的来源标记」，
 * 用于筛选与归档，<b>不作为上下文边界</b>。
 *
 * <h3>归属校验是唯一入口</h3>
 * 所有按会话 ID 的操作都必须走 {@link #requireOwned}，而不是 {@code findById}——
 * 否则学生 A 拿到学生 B 的会话 ID 就能读别人的对话。
 */
@Service
public class AiConversationService {

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);

    /** 单会话消息数上限。超出后拒绝继续提问，提示新建会话（M4 §3.5）。 */
    public static final int MAX_MESSAGES_PER_CONVERSATION = 200;

    /** 单学生会话数上限。防止脚本刷出一堆空会话把列表撑爆。 */
    private static final int MAX_CONVERSATIONS_PER_STUDENT = 50;

    private static final int MAX_TITLE_LENGTH = 100;
    private static final String DEFAULT_TITLE = "新会话";

    private final AiConversationRepository conversationRepository;
    private final QaRecordRepository qaRecordRepository;
    private final CoursewareRepository coursewareRepository;
    private final ClassSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AiConversationService(AiConversationRepository conversationRepository,
                                 QaRecordRepository qaRecordRepository,
                                 CoursewareRepository coursewareRepository,
                                 ClassSessionRepository sessionRepository,
                                 UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.qaRecordRepository = qaRecordRepository;
        this.coursewareRepository = coursewareRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /** 我的会话列表，最近活跃的排最前。 */
    @Transactional(readOnly = true)
    public List<AiConversationResponse> list(Long studentId) {
        List<AiConversation> conversations =
                conversationRepository.findByStudentIdOrderByLastActiveAtDescIdDesc(studentId);
        if (conversations.isEmpty()) {
            return List.of();
        }

        // 一次性把条数查回来，避免列表里每个会话各 count 一次（N+1）
        List<Long> ids = conversations.stream().map(AiConversation::getId).toList();
        Map<Long, Long> counts = qaRecordRepository.countByConversationIds(ids).stream()
                .collect(Collectors.toMap(ConversationCount::conversationId,
                        ConversationCount::messageCount,
                        (a, b) -> a));

        return conversations.stream()
                .map(c -> AiConversationResponse.from(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /** 新建会话。 */
    @Transactional
    public AiConversationResponse create(Long studentId, CreateConversationRequest request) {
        long existing = conversationRepository
                .findByStudentIdOrderByLastActiveAtDescIdDesc(studentId).size();
        if (existing >= MAX_CONVERSATIONS_PER_STUDENT) {
            throw new BusinessException("会话数量已达上限（" + MAX_CONVERSATIONS_PER_STUDENT
                    + " 个），请先删除一些不再需要的会话");
        }

        AiConversation conversation = new AiConversation();
        conversation.setStudent(userRepository.getReferenceById(studentId));

        if (request != null && request.coursewareId() != null) {
            conversation.setCourseware(coursewareRepository
                    .findById(request.coursewareId())
                    .orElseThrow(() -> new BusinessException(404, "课件不存在")));
        }
        if (request != null && request.sessionId() != null) {
            conversation.setSession(sessionRepository
                    .findById(request.sessionId())
                    .orElseThrow(() -> new BusinessException(404, "课堂不存在")));
        }

        conversation.setTitle(resolveTitle(request == null ? null : request.title(),
                conversation.getCourseware() == null ? null : conversation.getCourseware().getName()));
        conversation.setLastActiveAt(now());

        AiConversation saved = conversationRepository.save(conversation);
        log.info("conversation_created id={} studentId={} coursewareId={}",
                saved.getId(), studentId, saved.getCourseware() == null ? null : saved.getCourseware().getId());

        return AiConversationResponse.from(saved, 0L);
    }

    /** 会话详情 + 全部消息。 */
    @Transactional(readOnly = true)
    public ConversationDetailResponse detail(Long studentId, Long conversationId) {
        AiConversation conversation = requireOwned(studentId, conversationId);

        List<QaRecordResponse> messages = withTurn(
                qaRecordRepository.findByConversationId(conversationId).stream()
                        .map(QaRecordResponse::from)
                        .toList());

        return ConversationDetailResponse.of(
                AiConversationResponse.from(conversation, (long) messages.size()),
                messages);
    }

    /** 重命名。 */
    @Transactional
    public AiConversationResponse rename(Long studentId, Long conversationId, String rawTitle) {
        AiConversation conversation = requireOwned(studentId, conversationId);

        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.isEmpty()) {
            throw new BusinessException("会话名称不能为空");
        }
        conversation.setTitle(truncate(title, MAX_TITLE_LENGTH));
        AiConversation saved = conversationRepository.save(conversation);

        long count = qaRecordRepository.countByConversationId(conversationId);
        return AiConversationResponse.from(saved, count);
    }

    /** 删除（软删除）。会话里的问答记录**保留**——它们是课堂记录的数据源。 */
    @Transactional
    public void remove(Long studentId, Long conversationId) {
        AiConversation conversation = requireOwned(studentId, conversationId);
        conversation.setDeleted(true);
        conversationRepository.save(conversation);
        log.info("conversation_deleted id={} studentId={}", conversationId, studentId);
    }

    // ── 供 QaService 使用 ─────────────────────────────────────

    /**
     * 取会话并校验归属。<b>所有按会话 ID 的操作都必须走这里。</b>
     *
     * <p>不匹配时抛 404 而不是 403：403 等于告诉对方「这个 ID 确实存在」，
     * 反而泄露了信息。统一按「不存在」处理。
     */
    @Transactional(readOnly = true)
    public AiConversation requireOwned(Long studentId, Long conversationId) {
        return conversationRepository.findByIdAndStudentId(conversationId, studentId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在"));
    }

    /**
     * 取该学生在指定课件下的「默认会话」，没有就建一个。
     *
     * <p>这是「老前端不传 conversationId 也能继续跑」的落点（M4 §2）。
     * 找不到匹配会话时**自动新建**，而不是报错——学生只是想问个问题，
     * 不该因为「还没建过会话」而失败。
     *
     * <p>必须在调用方的事务里执行（{@code REQUIRED} 会加入现有事务）：
     * 会话的创建与问答的落库应当同生共死，否则会留下一个没有内容的空会话。
     */
    @Transactional
    public AiConversation resolveDefault(Long studentId, Long coursewareId, Long sessionId) {
        return conversationRepository.findByStudentIdOrderByLastActiveAtDescIdDesc(studentId).stream()
                .filter(c -> sameId(c.getCourseware() == null ? null : c.getCourseware().getId(), coursewareId))
                .filter(c -> sameId(c.getSession() == null ? null : c.getSession().getId(), sessionId))
                .max(Comparator.comparing(AiConversation::getId))
                .orElseGet(() -> createFor(studentId, coursewareId, sessionId));
    }

    /** 刷新最后活跃时间。列表按它倒序，不刷新的话刚聊过的会话会被挤到下面。 */
    @Transactional
    public void touch(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.setLastActiveAt(now());
            conversationRepository.save(conversation);
        });
    }

    // ── 内部 ───────────────────────────────────────────────────

    private AiConversation createFor(Long studentId, Long coursewareId, Long sessionId) {
        var courseware = coursewareId == null ? null
                : coursewareRepository.findById(coursewareId).orElse(null);
        var session = sessionId == null ? null
                : sessionRepository.findById(sessionId).orElse(null);

        AiConversation conversation = new AiConversation();
        conversation.setStudent(userRepository.getReferenceById(studentId));
        conversation.setCourseware(courseware);
        conversation.setSession(session);
        conversation.setTitle(resolveTitle(null, courseware == null ? null : courseware.getName()));
        conversation.setLastActiveAt(now());

        AiConversation saved = conversationRepository.save(conversation);
        log.info("conversation_auto_created id={} studentId={} coursewareId={} sessionId={}",
                saved.getId(), studentId, coursewareId, sessionId);
        return saved;
    }

    /**
     * 给消息补上轮次序号（从 1 开始）。
     *
     * <p>用序号而不是另查一次库：查询本来就是正序的，
     * 第 i 条的位置就是它的轮次，多查一次纯属浪费。
     */
    private List<QaRecordResponse> withTurn(List<QaRecordResponse> messages) {
        return java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(i -> {
                    QaRecordResponse m = messages.get(i);
                    return new QaRecordResponse(m.id(), m.conversationId(), m.pageId(), m.pageNo(),
                            m.question(), m.answer(), m.status(), i + 1, m.askedAt());
                })
                .toList();
    }

    private static boolean sameId(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 缺省用课件名当会话名；再兜一层默认值，避免出现空标题。 */
    private String resolveTitle(String title, String coursewareName) {
        String value = title == null ? null : title.strip();
        if (value == null || value.isEmpty()) {
            value = (coursewareName == null || coursewareName.isBlank()) ? DEFAULT_TITLE : coursewareName;
        }
        return truncate(value, MAX_TITLE_LENGTH);
    }

    /** 按码点截断，避免把 emoji 切成半个字符。 */
    private static String truncate(String value, int maxChars) {
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxChars));
    }

    /** 与 ClassSessionService / QaService 同一条规矩：DATETIME 只有秒精度，先截断再存。 */
    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }
}
