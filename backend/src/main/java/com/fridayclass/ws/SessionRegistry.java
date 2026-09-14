package com.fridayclass.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 课堂 → 在线连接 的登记表。<b>单机内存实现。</b>
 *
 * <p>MVP 只有一台后端，够用。若将来要多实例部署，这里要换成 Redis pub/sub 之类，
 * 否则 A 实例上的翻页广播不到 B 实例上的学生。类注释先记着，免得到时踩。
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<Long, Set<WebSocketSession>> bySession = new ConcurrentHashMap<>();

    /** 登记一个已通过认证的连接。 */
    public void add(Long sessionId, WebSocketSession session) {
        bySession.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /** 断开时摘除。集合空了就顺手把 key 删掉，避免长期运行攒下一堆空集合。 */
    public void remove(Long sessionId, WebSocketSession session) {
        Set<WebSocketSession> connections = bySession.get(sessionId);
        if (connections == null) {
            return;
        }
        connections.remove(session);
        // 用 (key, value) 双参数版本：只有当这个 key 仍映射到同一个集合时才删，
        // 否则可能把「刚刚重新建好的新集合」误删（并发下的经典竞态）。
        if (connections.isEmpty()) {
            bySession.remove(sessionId, connections);
        }
    }

    /** 当前在线数，仅用于日志与调试。 */
    public int count(Long sessionId) {
        Set<WebSocketSession> connections = bySession.get(sessionId);
        return connections == null ? 0 : connections.size();
    }

    /**
     * 广播给该课堂的所有在线连接。
     *
     * <p>单个连接发送失败只记日志、不影响其他人——某个学生网络抖动不该让全班收不到。
     */
    public void broadcast(Long sessionId, String payload) {
        Set<WebSocketSession> connections = bySession.get(sessionId);
        if (connections == null || connections.isEmpty()) {
            log.debug("page_broadcast_no_subscriber sessionId={}", sessionId);
            return;
        }

        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : connections) {
            sendQuietly(session, message);
        }
        log.debug("page_broadcast sessionId={} receivers={} payload={}",
                sessionId, connections.size(), payload);
    }

    /**
     * 发送单条消息，失败不抛。
     *
     * <p><b>为什么要 synchronized</b>：{@link WebSocketSession#sendMessage} 不是线程安全的。
     * 两个线程同时往同一连接发消息会抛
     * {@code IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]}。
     * 翻页广播 + 心跳应答 + 错误提示都可能并发落到同一个 session 上，所以必须串行化。
     */
    private void sendQuietly(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (Exception ex) {
            log.warn("ws_send_failed wsSessionId={} reason={}", session.getId(), ex.getMessage());
        }
    }
}
