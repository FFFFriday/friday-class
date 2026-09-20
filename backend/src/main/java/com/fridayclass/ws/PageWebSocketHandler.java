package com.fridayclass.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.User;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.security.JwtService;
import com.fridayclass.service.ChatService;
import com.fridayclass.service.SessionParticipantService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 翻页广播的 WebSocket 端点（{@code /ws/page?sessionId=1}）。
 *
 * <h2>为什么鉴权放在「首帧」而不是握手</h2>
 * 浏览器原生的 {@code new WebSocket(url)} <b>无法设置请求头</b>——这是浏览器规范限制，
 * 不是代码问题。所以 REST 那套 {@code Authorization: Bearer} 在 WebSocket 上根本用不上。
 * 于是握手阶段放行（{@code SecurityConfig} 里 {@code /ws/**} 为 permitAll），
 * 连上后要求客户端在 5 秒内发第一帧：
 *
 * <pre>{"type":"auth","token":"&lt;JWT&gt;"}</pre>
 *
 * 超时未认证直接断开，避免有人开一堆连接白占资源。
 *
 * <h2>为什么不能把角色放在 URL 里</h2>
 * 契约原文写的是 {@code /ws/page?sessionId=1&role=TEACHER}。但 {@code role} 由客户端自报，
 * <b>等于没有鉴权</b>——学生把 URL 改一下就能冒充老师广播假页码，把全班的 AI 上下文带偏。
 * 本实现只认「服务端用密钥验过的 JWT」里的 role。
 *
 * <h2>本端点只下发，不接收翻页</h2>
 * 翻页走 {@code POST /api/session/{id}/page}。这样老师那边 WS 断了也照样能翻页，
 * 而且顺带把「伪造页码」这个攻击面整个消掉了——服务端根本不接受来自连接的消息指令。
 *
 * <h2>消息协议</h2>
 * <pre>
 * 客户端 → 服务端   {"type":"auth","token":"..."}   认证（必须在超时前发）
 *                 {"type":"ping"}                  心跳
 * 服务端 → 客户端   {"type":"auth_ok", ...}          认证成功
 *                 {"type":"page","pageNo":3,"serverTime":"..."}  老师翻页了
 *                 {"type":"pong"}                   心跳应答
 *                 {"type":"error","message":"..."}  出错
 * </pre>
 */
@Component
public class PageWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PageWebSocketHandler.class);

    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_NICKNAME = "nickname";
    private static final String ATTR_AUTHENTICATED = "authenticated";
    private static final String ATTR_CONNECTED_AT = "connectedAt";

    /** 连上后多久必须完成认证。 */
    private static final long AUTH_TIMEOUT_MS = 5_000L;

    /**
     * 扫描间隔，**必须远小于 {@link #AUTH_TIMEOUT_MS}**。
     * 实测把它设成和超时一样（5 秒）时，最坏要等两个周期才清理掉，
     * 写着"5 秒超时"实际 7.5 秒才断。改成 1 秒后实测贴近 5 秒。
     */
    private static final long AUTH_SWEEP_INTERVAL_MS = 1_000L;

    private final SessionRegistry registry;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ClassSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final ClassBroadcaster broadcaster;
    private final ChatService chatService;
    private final SessionParticipantService participantService;

    /** 尚未认证的连接，供定时清理，防止「连上不说话」的堆积。 */
    private final Set<WebSocketSession> pendingAuth = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ws-auth-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    public PageWebSocketHandler(SessionRegistry registry,
                                JwtService jwtService,
                                UserRepository userRepository,
                                ClassSessionRepository sessionRepository,
                                ObjectMapper objectMapper,
                                ClassBroadcaster broadcaster,
                                ChatService chatService,
                                SessionParticipantService participantService) {
        this.registry = registry;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.chatService = chatService;
        this.participantService = participantService;
    }

    @PostConstruct
    void startAuthSweeper() {
        sweeper.scheduleAtFixedRate(this::closeExpiredPending,
                AUTH_SWEEP_INTERVAL_MS, AUTH_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopAuthSweeper() {
        sweeper.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long sessionId = parseSessionId(session);
        if (sessionId == null) {
            close(session, CloseStatus.BAD_DATA.withReason("missing sessionId"));
            return;
        }
        if (!sessionRepository.existsById(sessionId)) {
            // 提前告知「课堂不存在」，比让学生在页面上干等一个永远不会来的消息强
            close(session, CloseStatus.BAD_DATA.withReason("session not found"));
            return;
        }

        session.getAttributes().put(ATTR_SESSION_ID, sessionId);
        session.getAttributes().put(ATTR_CONNECTED_AT, System.currentTimeMillis());
        session.getAttributes().put(ATTR_AUTHENTICATED, Boolean.FALSE);
        pendingAuth.add(session);

        log.debug("ws_connected wsSessionId={} sessionId={}", session.getId(), sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            sendError(session, "消息不是合法 JSON");
            return;
        }

        String type = root.path("type").asText("");

        if (!isAuthenticated(session)) {
            if (!"auth".equals(type)) {
                sendError(session, "请先发送 {\"type\":\"auth\",\"token\":\"...\"} 完成认证");
                return;
            }
            authenticate(session, root.path("token").asText(""));
            return;
        }

        switch (type) {
            case "ping" -> {
                sendJson(session, Map.of("type", "pong"));
                // 心跳顺手刷新出席记录的心跳时间。失败不影响 pong——
                // 心跳是连接保活，不能因为一次数据库写失败就断掉。
                touchQuietly(session);
            }
            case "auth" -> sendError(session, "该连接已认证，无需重复认证");
            case "page" -> sendError(session, "翻页请调用 POST /api/session/{id}/page；WebSocket 只用于接收");
            case "chat.send" -> handleChatSend(session, root);
            // 信令三个方向都要能上行。**offer 同样要在这里**：
            // 协商是老师发起的，所以 offer 既是服务端→学生的事件、
            // 也是老师→服务端的上行消息。协议 §1.2 最初漏了它，
            // 漏掉的表现是「老师点了共享、学生一直黑屏」——因为老师的
            // offer 被下面 default 分支当成未知类型丢掉了。
            case "webrtc.offer" -> relaySignal(session, root, "webrtc.offer", "sdp");
            case "webrtc.answer" -> relaySignal(session, root, "webrtc.answer", "sdp");
            case "webrtc.ice" -> relaySignal(session, root, "webrtc.ice", "candidate");
            case "webrtc.ready" -> handleWebrtcReady(session);
            default -> sendError(session, "未知消息类型：" + type);
        }
    }

    // ── 讨论区（M2） ───────────────────────────────────────────

    /**
     * 学生/老师发一条讨论区消息。
     *
     * <p><b>失败不回滚、不重发</b>：发不出去就回一条 {@code error}，
     * 前端提示「慢一点」即可。讨论区丢一条无所谓——这是它走 WS 而不是
     * POST 的前提（见技术约定 §1.1）。
     */
    private void handleChatSend(WebSocketSession session, JsonNode root) {
        Long sessionId = attrSessionId(session);
        Long userId = attrUserId(session);
        if (sessionId == null || userId == null) {
            sendError(session, "连接尚未完成认证");
            return;
        }

        String content = root.hasNonNull("content") ? root.get("content").asText() : "";
        String clientMsgId = root.hasNonNull("clientMsgId") ? root.get("clientMsgId").asText() : null;
        Long pageId = root.hasNonNull("pageId") ? root.get("pageId").asLong() : null;

        try {
            // 返回 null 表示这条是重复消息、已被吞掉。**刻意不做任何回应**：
            // 对重复消息回一条 error，前端会以为发失败了，反而更乱。
            chatService.send(sessionId, userId, content, clientMsgId, pageId);
        } catch (BusinessException ex) {
            // 业务失败（限流、内容为空、课堂已结束）带上 code，
            // 前端据此区分「慢一点」和「真出错了」——与 REST 的 bizCode 同一套语义
            sendError(session, ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("ws_chat_send_failed sessionId={} userId={} reason={}",
                    sessionId, userId, ex.toString());
            sendError(session, "发言发送失败，请稍后重试");
        }
    }

    // ── WebRTC 信令（M1） ──────────────────────────────────────

    /**
     * 转发一条信令（offer / answer / ICE）给指定用户。
     *
     * <p>服务端<b>不解析</b> SDP / ICE 的内容，只按 {@code toUserId} 原样转发。
     * 这样后端与具体编解码、协商方式完全解耦，将来换方案不用改这里。
     *
     * @param type  下行事件名，与上行同名（{@code webrtc.offer} / {@code webrtc.answer} / {@code webrtc.ice}）
     * @param field 载荷字段名（SDP 用 {@code sdp}，ICE 用 {@code candidate}）
     */
    private void relaySignal(WebSocketSession session, JsonNode root, String type, String field) {
        Long sessionId = attrSessionId(session);
        Long userId = attrUserId(session);
        Long toUserId = root.hasNonNull("toUserId") ? root.get("toUserId").asLong() : null;
        JsonNode payloadNode = root.get(field);

        if (sessionId == null || userId == null || toUserId == null
                || payloadNode == null || payloadNode.isNull()) {
            sendError(session, "信令参数不完整");
            return;
        }

        boolean delivered = switch (type) {
            case "webrtc.offer" -> broadcaster.webrtcOffer(sessionId, toUserId, userId, payloadNode);
            case "webrtc.answer" -> broadcaster.webrtcAnswer(sessionId, toUserId, userId, payloadNode);
            case "webrtc.ice" -> broadcaster.webrtcIce(sessionId, toUserId, userId, payloadNode);
            default -> false;
        };

        if (!delivered) {
            // 对方已经掉线。这里**刻意不回 error**：协商过程中对端离线是常态
            // （学生刷新页面），回了会让老师端满屏报错，而正确的处理是
            // 等对方重新进课堂发 presence.join 后重建连接。
            log.debug("webrtc_signal_dropped sessionId={} toUserId={} fromUserId={} field={}",
                    sessionId, toUserId, userId, field);
        }
    }

    /** 学生说「我准备好了」→ 通知老师开始协商。 */
    private void handleWebrtcReady(WebSocketSession session) {
        Long sessionId = attrSessionId(session);
        Long userId = attrUserId(session);
        if (sessionId == null || userId == null) {
            return;
        }
        Long teacherId = participantService.teacherIdOf(sessionId);
        if (teacherId == null) {
            log.debug("webrtc_ready_no_teacher sessionId={}", sessionId);
            return;
        }
        broadcaster.webrtcReady(sessionId, teacherId, userId);
    }

    /** 刷新出席心跳，失败只记日志。 */
    private void touchQuietly(WebSocketSession session) {
        Long sessionId = attrSessionId(session);
        Long userId = attrUserId(session);
        if (sessionId == null || userId == null) {
            return;
        }
        try {
            participantService.touch(sessionId, userId);
        } catch (Exception ex) {
            log.debug("participant_touch_failed sessionId={} userId={} reason={}",
                    sessionId, userId, ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        pendingAuth.remove(session);
        Long sessionId = attrSessionId(session);
        Long userId = attrUserId(session);

        // 两个属性都齐了才能做后续处理：登记表是按 (课堂, 用户) 索引的，
        // 只知道课堂不知道人就没法定位到那一格。
        if (sessionId == null || userId == null) {
            log.debug("ws_closed wsSessionId={} sessionId={} userId={} status={}",
                    session.getId(), sessionId, userId, status);
            return;
        }

        registry.remove(sessionId, userId, session);

        // 只有「这个人一条连接都不剩」才算离开。
        // 学生开两个标签页时，关掉其中一个不该广播 presence.leave——
        // 否则老师端会以为他走了、把 WebRTC 连接拆掉，剩下那个标签页直接黑屏。
        if (registry.onlineUserIds(sessionId).contains(userId)) {
            log.debug("ws_closed_but_still_online wsSessionId={} sessionId={} userId={}",
                    session.getId(), sessionId, userId);
            return;
        }

        try {
            participantService.leave(sessionId, userId);
        } catch (Exception ex) {
            log.warn("participant_leave_failed sessionId={} userId={} reason={}",
                    sessionId, userId, ex.toString());
        }

        broadcaster.presenceLeave(sessionId, userId, attrNickname(session),
                registry.userCount(sessionId));

        log.debug("ws_closed wsSessionId={} sessionId={} userId={} status={}",
                session.getId(), sessionId, userId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("ws_transport_error wsSessionId={} reason={}", session.getId(), exception.getMessage());
        close(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 校验 JWT 并登记连接。
     *
     * <p>校验口径与 {@code JwtAuthenticationFilter} 完全一致——**不只是验签名**，
     * 还要查库确认用户仍在、未软删除、且令牌版本号没被改密码作废。
     * 两处逻辑必须一致，否则会出现「HTTP 已失效但 WebSocket 还认」的漏洞。
     */
    private void authenticate(WebSocketSession session, String token) {
        if (token == null || token.isBlank()) {
            sendError(session, "缺少 token");
            return;
        }

        User user;
        try {
            Claims claims = jwtService.parse(token);
            String username = claims.getSubject();
            Integer tokenVersion = claims.get("ver", Integer.class);
            user = userRepository.findByUsernameAndDeletedFalse(username)
                    .filter(found -> tokenVersion != null
                            && tokenVersion.equals(found.getTokenVersion()))
                    .orElse(null);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("ws_auth_invalid reason={}", ex.getMessage());
            user = null;
        }

        if (user == null) {
            sendError(session, "认证失败：令牌无效或已过期");
            close(session, CloseStatus.POLICY_VIOLATION.withReason("auth failed"));
            return;
        }

        Long sessionId = attrSessionId(session);
        if (sessionId == null) {
            close(session, CloseStatus.BAD_DATA.withReason("missing sessionId"));
            return;
        }

        session.getAttributes().put(ATTR_AUTHENTICATED, Boolean.TRUE);
        session.getAttributes().put(ATTR_USER_ID, user.getId());
        // 昵称存进属性：连接关闭时要广播 presence.leave，那时不能再查库
        // （用户可能已被删除，或事务上下文已不可用）。
        session.getAttributes().put(ATTR_NICKNAME, user.getNickname());
        pendingAuth.remove(session);
        registry.add(sessionId, user.getId(), session);

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "auth_ok");
        ack.put("sessionId", sessionId);
        ack.put("userId", user.getId());
        ack.put("role", user.getRole().name());
        // online = 在线「人数」，按用户去重：同一个人开两个标签页只算一个。
        // 原先是「连接数」，单标签页下两者相同、多标签页下会虚高。
        // 另附 connections 供排查连接泄漏时对照。前端目前不读这两个字段。
        ack.put("online", registry.userCount(sessionId));
        ack.put("connections", registry.count(sessionId));
        sendJson(session, ack);

        // 登记出席。幂等：重复进入（刷新、重连）只更新心跳，不新增行。
        // 失败不阻断连接——出席统计出问题不该让人进不了课堂。
        try {
            participantService.join(sessionId, user.getId(), user.getRole());
        } catch (Exception ex) {
            log.warn("participant_join_failed sessionId={} userId={} reason={}",
                    sessionId, user.getId(), ex.toString());
        }

        // 广播「有人进来了」。**发给全课堂，含刚进来的这位本人**——
        // 前端按 userId 过滤掉自己即可。若刻意排除他，会出现
        // 「第一个进课堂的人永远不知道自己在不在线」这种怪状态。
        broadcaster.presenceJoin(sessionId, user.getId(),
                user.getNickname(), user.getRole().name(), registry.userCount(sessionId));

        log.info("ws_authenticated sessionId={} userId={} role={} online={}",
                sessionId, user.getId(), user.getRole().name(), registry.userCount(sessionId));
    }

    private boolean isAuthenticated(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED));
    }

    private Long attrSessionId(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_SESSION_ID);
        return value instanceof Long id ? id : null;
    }

    private Long attrUserId(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_USER_ID);
        return value instanceof Long id ? id : null;
    }

    private String attrNickname(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_NICKNAME);
        return value instanceof String name ? name : null;
    }

    /** 从 {@code ?sessionId=1} 取课堂 ID；缺失或非法返回 null。 */
    private Long parseSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String raw = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().getFirst("sessionId");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.strip());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 清理超时未认证的连接。Iterator 是弱一致的，边遍历边关闭是安全的。 */
    private void closeExpiredPending() {
        long now = System.currentTimeMillis();
        for (WebSocketSession session : pendingAuth) {
            Object connectedAt = session.getAttributes().get(ATTR_CONNECTED_AT);
            if (connectedAt instanceof Long start && now - start > AUTH_TIMEOUT_MS) {
                log.debug("ws_auth_timeout wsSessionId={}", session.getId());
                close(session, CloseStatus.POLICY_VIOLATION.withReason("auth timeout"));
            }
        }
    }

    private void sendError(WebSocketSession session, String message) {
        sendError(session, 400, message);
    }

    /**
     * 带业务码的错误。前端据此区分「慢一点就好」（429）与「真出错了」，
     * 与 REST 侧 {@code ApiResponse.code} 是同一套语义。
     */
    private void sendError(WebSocketSession session, int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("code", code);
        payload.put("message", message);
        sendJson(session, payload);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        try {
            send(session, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("ws_send_json_failed wsSessionId={}", session.getId(), ex);
        }
    }

    /**
     * 发送单帧。与 {@code SessionRegistry} 里同样的理由必须 {@code synchronized}：
     * {@link WebSocketSession#sendMessage} 不是线程安全的，翻页广播与本类的应答
     * 可能同时落到同一连接上。
     */
    private void send(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ex) {
            log.warn("ws_send_failed wsSessionId={} reason={}", session.getId(), ex.getMessage());
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ex) {
            log.debug("ws_close_failed wsSessionId={} reason={}", session.getId(), ex.getMessage());
        }
    }
}
