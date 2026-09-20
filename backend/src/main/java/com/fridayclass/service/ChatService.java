package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.ChatHistoryResponse;
import com.fridayclass.dto.ChatMessageResponse;
import com.fridayclass.entity.ChatMessage;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.entity.User;
import com.fridayclass.enums.ChatMessageStatus;
import com.fridayclass.enums.Role;
import com.fridayclass.enums.SessionStatus;
import com.fridayclass.repository.ChatMessageRepository;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.ws.ClassBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 课堂讨论区业务（M2）。
 *
 * <h3>为什么发送走 WebSocket 而拉历史走 REST</h3>
 * 沿用项目既有取舍：<b>状态类、不能丢的走 POST/GET；高频、可容忍丢失的走 WS</b>。
 * 讨论区消息丢一条无所谓，翻页不能丢（详情见技术约定 §1.1）。
 * 所以发送走 {@code chat.send} 低延迟，拉历史和删除走 REST。
 *
 * <h3>事务边界</h3>
 * 与其它服务一致：<b>先落库提交，再广播</b>。广播必须在事务外调用，
 * 否则事务回滚了消息却已经发出去，学生会看到一条数据库里并不存在的发言。
 *
 * <h3>XSS</h3>
 * 内容<b>原样存储、不做 HTML 转义</b>（转义是渲染层的责任），前端必须用
 * {@code {{ }}} 插值。后端若在这里转义，学生看到的就是 {@code &lt;script&gt;} 字面量，
 * 属于把上层的锅背到自己身上、而且背错了地方。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 与 chat_message.content 的列长度一致。 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    /** 每人 1 条 / 2 秒。防的是「一个班同时刷屏」的瞬时并发。 */
    private static final long MIN_SEND_INTERVAL_MS = 2_000L;

    /**
     * {@code clientMsgId} 去重窗口。
     * 只防「网络重发 / 手抖连点」，不需要很长——同一 id 隔一分钟再来，
     * 用户自己也知道那是另一次发言了。
     */
    private static final long DEDUP_WINDOW_MS = 60_000L;

    /** 去重表条目上限，超了整体清空（课堂场景条目很少，粗略但不会无界增长）。 */
    private static final int MAX_DEDUP_ENTRIES = 5_000;

    private static final int DEFAULT_LATEST_LIMIT = 50;
    private static final int DEFAULT_INCREMENTAL_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final ChatMessageRepository chatMessageRepository;
    private final ClassSessionRepository sessionRepository;
    private final CoursewarePageRepository pageRepository;
    private final UserRepository userRepository;
    private final ClassBroadcaster broadcaster;
    private final TransactionTemplate transactionTemplate;

    /**
     * 每人上次发言时刻（毫秒）。
     *
     * <p>⚠️ 进程内状态：没有 Redis（项目宪章硬约束），重启清零、多实例各算一份。
     * 与 {@code QaService} 的限流是同一套取舍，不是可靠限流，够用即可。
     */
    private final Map<Long, Long> lastSendAtMs = new ConcurrentHashMap<>();

    /** 已处理过的客户端消息 ID → 过期时刻（毫秒）。 */
    private final Map<String, Long> seenClientMsgIds = new ConcurrentHashMap<>();

    public ChatService(ChatMessageRepository chatMessageRepository,
                       ClassSessionRepository sessionRepository,
                       CoursewarePageRepository pageRepository,
                       UserRepository userRepository,
                       ClassBroadcaster broadcaster,
                       PlatformTransactionManager transactionManager) {
        this.chatMessageRepository = chatMessageRepository;
        this.sessionRepository = sessionRepository;
        this.pageRepository = pageRepository;
        this.userRepository = userRepository;
        this.broadcaster = broadcaster;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 发一条发言（由 WebSocket 的 {@code chat.send} 调用）。
     *
     * @return 落库后的发言；若是重复消息（{@code clientMsgId} 命中）则返回 null，
     *         调用方据此**不再广播**——重复的那条本来就该被吃掉
     */
    public ChatMessageResponse send(Long sessionId, Long userId,
                                    String rawContent, String clientMsgId, Long pageId) {
        // 1) 幂等：重复消息直接吃掉。
        //    放在限流之前——学生连点「发送」不该被判成「发太快了」，
        //    那次重复本来就该被幂等地吞掉。（与 QaService.ask 的顺序一致）
        if (isDuplicate(clientMsgId)) {
            log.debug("chat_duplicate_ignored sessionId={} userId={} clientMsgId={}",
                    sessionId, userId, clientMsgId);
            return null;
        }

        // 2) 限流
        checkRateLimit(userId);

        String content = normalizeContent(rawContent);

        // 3) 短事务：校验课堂 + 落库 + **组装响应**
        //
        // ⚠ 响应必须在事务**内**组装，不能把实体带出来再转换。
        // ChatMessageResponse.from 要读 user.nickname，而 user 是
        // getReferenceById 拿到的懒代理；事务一关再读它就会抛
        // LazyInitializationException（"no session"）。
        //
        // 这不是理论风险：首轮实测就踩到了，症状极具迷惑性——
        // **消息成功进了库（历史里查得到），但全班都收不到广播**，
        // 因为异常抛在广播之前。宁可多一行缩进，也不要把这个坑留在那里。
        ChatMessageResponse response = transactionTemplate.execute(status -> {
            ChatMessage saved = doSave(sessionId, userId, content, pageId);
            return ChatMessageResponse.from(saved);
        });

        // 4) 事务提交后再广播
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", response.id());
        payload.put("userId", response.userId());
        payload.put("nickname", response.nickname());
        payload.put("role", response.role());
        payload.put("content", response.content());
        payload.put("pageId", response.pageId());
        payload.put("pageNo", response.pageNo());
        payload.put("createdAt", response.createdAt() == null ? null : response.createdAt().toString());
        broadcaster.chatNew(sessionId, payload);

        log.info("chat_sent sessionId={} userId={} messageId={} chars={}",
                sessionId, userId, response.id(), content.length());

        return response;
    }

    /**
     * 拉历史。三条路径按优先级：
     * <ol>
     *   <li>{@code beforeId} —— 往前翻，取比它更早的一页（「加载更早」）</li>
     *   <li>{@code afterId} —— 增量补拉，取比它更新的（断线重连）</li>
     *   <li>都不传 —— 首次进入，取<b>最近</b> N 条</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public ChatHistoryResponse history(Long sessionId, Long afterId, Long beforeId, Integer rawLimit) {
        sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));

        if (beforeId != null && beforeId > 0) {
            // 往前翻。倒序取 limit+1 条判断还有没有更早的，再反转成正序。
            int limit = clampLimit(rawLimit, DEFAULT_LATEST_LIMIT);
            List<ChatMessage> desc = chatMessageRepository.findWithUserBeforeId(
                    sessionId, beforeId, PageRequest.of(0, limit + 1));
            boolean hasMore = desc.size() > limit;
            List<ChatMessage> window = hasMore ? desc.subList(0, limit) : desc;

            List<ChatMessage> asc = new ArrayList<>(window);
            Collections.reverse(asc);
            return ChatHistoryResponse.of(toResponses(asc), hasMore);
        }

        if (afterId != null && afterId > 0) {
            // 增量路径：断线重连补拉。多取一条用于判断 hasMore。
            int limit = clampLimit(rawLimit, DEFAULT_INCREMENTAL_LIMIT);
            List<ChatMessage> rows = chatMessageRepository.findWithUserAfterId(
                    sessionId, afterId, PageRequest.of(0, limit + 1));
            boolean hasMore = rows.size() > limit;
            List<ChatMessage> page = hasMore ? rows.subList(0, limit) : rows;
            return ChatHistoryResponse.of(toResponses(page), hasMore);
        }

        // 首次进入：取**最近** N 条（倒序取出后反转成正序）。
        // 不能用「正序取前 N 条」——那是这节课最早的一批，学生一进来看到的是开场白。
        int limit = clampLimit(rawLimit, DEFAULT_LATEST_LIMIT);
        List<ChatMessage> desc = chatMessageRepository.findWithUserLatest(
                sessionId, PageRequest.of(0, limit + 1));
        boolean hasMore = desc.size() > limit;
        List<ChatMessage> window = hasMore ? desc.subList(0, limit) : desc;

        List<ChatMessage> asc = new ArrayList<>(window);
        Collections.reverse(asc);
        return ChatHistoryResponse.of(toResponses(asc), hasMore);
    }

    /**
     * 撤回一条发言（软删除）。
     *
     * <p>权限：本课堂的授课教师，或任意管理员。学生只能删自己的？——<b>不，学生不能删</b>。
     * 讨论区留痕是有意为之：允许学生「发了又删」等于给了一条规避事后追责的路。
     *
     * @param operatorRole 操作人角色名（{@code UserPrincipal#getRole()} 给的就是字符串）。
     *                     收字符串而不是枚举：那是从认证主体透传过来的，转枚举要 {@code valueOf}，
     *                     遇到意外取值会抛异常；而这里只需要判「是不是 ADMIN」一件事。
     * @return 被撤回发言所属的课堂 ID，供调用方广播
     */
    public Long delete(Long messageId, Long operatorId, String operatorRole) {
        Long sessionId = transactionTemplate.execute(
                status -> doDelete(messageId, operatorId, operatorRole));

        // 事务外广播
        broadcaster.chatDeleted(sessionId, messageId);
        return sessionId;
    }

    // ── 事务内 ─────────────────────────────────────────────────

    private ChatMessage doSave(Long sessionId, Long userId, String content, Long pageId) {
        ClassSession session = sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));

        if (session.getStatus() == SessionStatus.ENDED) {
            throw new BusinessException("课堂已结束，无法发言");
        }

        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setUser(userRepository.getReferenceById(userId));
        message.setPage(resolvePage(session, pageId));
        message.setContent(content);
        message.setStatus(ChatMessageStatus.NORMAL);
        return chatMessageRepository.save(message);
    }

    private Long doDelete(Long messageId, Long operatorId, String operatorRole) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(404, "发言不存在"));

        ClassSession session = message.getSession();
        Long sessionId = session == null ? null : session.getId();

        boolean isAdmin = Role.ADMIN.name().equals(operatorRole);
        boolean isSessionTeacher = session != null
                && session.getTeacher() != null
                && session.getTeacher().getId().equals(operatorId);

        if (!isAdmin && !isSessionTeacher) {
            throw new BusinessException(403, "只有本课堂的教师或管理员可以撤回发言");
        }

        if (message.getStatus() != ChatMessageStatus.DELETED) {
            message.setStatus(ChatMessageStatus.DELETED);
            message.setDeletedBy(operatorId);
            message.setDeletedAt(LocalDateTime.now().withNano(0));
            chatMessageRepository.save(message);
            log.info("chat_deleted messageId={} sessionId={} operatorId={}",
                    messageId, sessionId, operatorId);
        }

        return sessionId;
    }

    // ── 辅助 ───────────────────────────────────────────────────

    /**
     * 校验并规范化 {@code pageId}。
     *
     * <p>页面标签只是给课堂记录提供上下文，<b>不是发言的前置条件</b>。
     * 所以对不上时<b>不拒绝这条发言</b>，而是把标签置空并记一条日志——
     * 因为一个错页码就让学生发不出话，代价远大于少一个标签。
     */
    private CoursewarePage resolvePage(ClassSession session, Long pageId) {
        if (pageId == null) {
            return null;
        }
        CoursewarePage page = pageRepository.findById(pageId).orElse(null);
        if (page == null) {
            log.debug("chat_page_tag_dropped reason=page_not_found pageId={}", pageId);
            return null;
        }
        Courseware courseware = session.getCourseware();
        if (courseware == null || page.getCourseware() == null
                || !courseware.getId().equals(page.getCourseware().getId())) {
            log.debug("chat_page_tag_dropped reason=courseware_mismatch pageId={} sessionId={}",
                    pageId, session.getId());
            return null;
        }
        return page;
    }

    private List<ChatMessageResponse> toResponses(List<ChatMessage> rows) {
        return rows.stream().map(ChatMessageResponse::from).toList();
    }

    /**
     * 内容规范化：去首尾空白、校验非空、按<b>码点</b>截断到列长度。
     *
     * <p>按码点而不是按 char 截断：{@code String.substring} 按 UTF-16 码元切，
     * 遇到 emoji（代理对）会把它劈成半个字符，存进库就是乱码。
     */
    private String normalizeContent(String rawContent) {
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw new BusinessException("发言内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_LENGTH) {
            int end = content.offsetByCodePoints(0, MAX_CONTENT_LENGTH);
            content = content.substring(0, end);
        }
        return content;
    }

    private int clampLimit(Integer rawLimit, int fallback) {
        if (rawLimit == null || rawLimit <= 0) {
            return fallback;
        }
        return Math.min(rawLimit, MAX_LIMIT);
    }

    /**
     * 幂等判定。命中返回 true 并<b>刷新过期时间</b>——
     * 学生按住发送键连点时，每次都该被吞掉，而不是每隔 60 秒漏进去一条。
     */
    private boolean isDuplicate(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long expiresAt = seenClientMsgIds.get(clientMsgId);
        if (expiresAt != null && expiresAt > now) {
            seenClientMsgIds.put(clientMsgId, now + DEDUP_WINDOW_MS);
            return true;
        }
        if (seenClientMsgIds.size() >= MAX_DEDUP_ENTRIES) {
            seenClientMsgIds.clear();
        }
        seenClientMsgIds.put(clientMsgId, now + DEDUP_WINDOW_MS);
        return false;
    }

    /**
     * 每人发言限流。与 QaService 同款「先读后写、不加锁」——
     * 最坏情况是并发两条都通过，多发了一条而已，不影响正确性。
     */
    private void checkRateLimit(Long userId) {
        long now = System.currentTimeMillis();
        Long last = lastSendAtMs.get(userId);
        if (last != null && now - last < MIN_SEND_INTERVAL_MS) {
            throw new BusinessException(429, "发言太快了，慢一点再说");
        }
        lastSendAtMs.put(userId, now);
    }
}
