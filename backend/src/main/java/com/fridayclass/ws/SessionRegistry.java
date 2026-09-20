package com.fridayclass.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 课堂 → 在线连接 的登记表。<b>单机内存实现。</b>
 *
 * <p>MVP 只有一台后端，够用。若将来要多实例部署，这里要换成 Redis pub/sub 之类，
 * 否则 A 实例上的翻页广播不到 B 实例上的学生。类注释先记着，免得到时踩。
 *
 * <h2>为什么是三层结构（课堂 → 用户 → 连接）</h2>
 * 最初只有「课堂 → 连接集合」，够翻页广播用。但新增需求要求<b>按人定点投递</b>：
 * <ul>
 *   <li>踢人：服务端只关掉<b>某个学生</b>的连接，不能误伤全班；</li>
 *   <li>屏幕共享信令：老师的 offer 要给到<b>指定学生</b>，不是广播；</li>
 *   <li>在线名单：要能回答「现在有<b>哪几个人</b>在」，而不只是「有几个连接」。</li>
 * </ul>
 * 所以中间加一层 userId。最内层仍然是集合而非单个连接——
 * <b>同一个学生开两个标签页是合法操作</b>，只留最后一条会让先开的那页
 * 永远收不到定向消息（踢不掉）。
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** 课堂 ID → 用户 ID → 该用户的全部连接。 */
    private final Map<Long, Map<Long, Set<WebSocketSession>>> bySession = new ConcurrentHashMap<>();

    /** 登记一个已通过认证的连接。 */
    public void add(Long sessionId, Long userId, WebSocketSession session) {
        if (sessionId == null || userId == null) {
            // ConcurrentHashMap 不接受 null 键，传进来会直接 NPE。
            // 调用方已有更早的判空，这里是最后一道闸。
            return;
        }
        bySession.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    /**
     * 断开时摘除。三层的空容器都要顺手清掉，避免长期运行攒下一堆空 map / 空 set。
     *
     * <p>每层都用「双参数 remove」：只有当 key 仍映射到<b>刚检查的那个对象</b>时才删。
     * 单参数版本存在经典竞态——并发下可能把「刚刚重新建好的新集合」误删，
     * 导致新连上的学生收不到消息。
     */
    public void remove(Long sessionId, Long userId, WebSocketSession session) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        if (users == null) {
            return;
        }
        Set<WebSocketSession> connections = users.get(userId);
        if (connections == null) {
            return;
        }
        connections.remove(session);
        if (connections.isEmpty()) {
            users.remove(userId, connections);
            if (users.isEmpty()) {
                bySession.remove(sessionId, users);
            }
        }
    }

    /** 当前连接数（含同一人的多个标签页）。仅用于日志与调试。 */
    public int count(Long sessionId) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        return users == null ? 0 : users.values().stream().mapToInt(Set::size).sum();
    }

    /** 当前在线<b>人数</b>（按用户去重）。在线名单用这个，不是 {@link #count}。 */
    public int userCount(Long sessionId) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        return users == null ? 0 : users.size();
    }

    /** 当前在线的用户 ID 集合（快照，可安全遍历）。 */
    public Set<Long> onlineUserIds(Long sessionId) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        return users == null ? Set.of() : Set.copyOf(users.keySet());
    }

    /**
     * 广播给该课堂的所有在线连接。
     *
     * <p>单个连接发送失败只记日志、不影响其他人——某个学生网络抖动不该让全班收不到。
     */
    public void broadcast(Long sessionId, String payload) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        if (users == null || users.isEmpty()) {
            log.debug("ws_broadcast_no_subscriber sessionId={}", sessionId);
            return;
        }

        TextMessage message = new TextMessage(payload);
        int receivers = 0;
        for (Set<WebSocketSession> connections : users.values()) {
            for (WebSocketSession session : connections) {
                sendQuietly(session, message);
                receivers++;
            }
        }
        log.debug("ws_broadcast sessionId={} receivers={} users={} payload={}",
                sessionId, receivers, users.size(), payload);
    }

    /**
     * 定点投递给某用户的<b>全部</b>连接（他可能开了多个标签页）。
     *
     * @return 是否至少送达一条连接；用户不在线时返回 false，由调用方决定怎么办
     *         （例如离线学生的屏幕共享信令直接丢弃，等他重连时再补）
     */
    public boolean sendToUser(Long sessionId, Long userId, String payload) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        if (users == null) {
            return false;
        }
        Set<WebSocketSession> connections = users.get(userId);
        if (connections == null || connections.isEmpty()) {
            return false;
        }

        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : connections) {
            sendQuietly(session, message);
        }
        log.debug("ws_send_to_user sessionId={} userId={} connections={}",
                sessionId, userId, connections.size());
        return true;
    }

    /**
     * 踢人：关闭某用户在本课堂的全部连接。
     *
     * <p>关连接会触发 {@code afterConnectionClosed}，登记表里的记录由那条路径负责摘除，
     * 这里不重复删——两处都删会踩并发竞态。
     *
     * @return 被关闭的连接数
     */
    public int closeUser(Long sessionId, Long userId, String reason) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        if (users == null) {
            return 0;
        }
        Set<WebSocketSession> connections = users.get(userId);
        if (connections == null || connections.isEmpty()) {
            return 0;
        }

        // 先复制一份再遍历：close() 会同步触发 afterConnectionClosed，
        // 那条路径正在改这个集合，直接遍历会抛 ConcurrentModificationException。
        Set<WebSocketSession> snapshot = Set.copyOf(connections);
        CloseStatus status = CloseStatus.POLICY_VIOLATION.withReason(
                reason == null || reason.isBlank() ? "kicked" : reason);
        for (WebSocketSession session : snapshot) {
            try {
                session.close(status);
            } catch (Exception ex) {
                log.debug("ws_kick_close_failed wsSessionId={} reason={}",
                        session.getId(), ex.getMessage());
            }
        }
        log.info("ws_user_kicked sessionId={} userId={} connections={}",
                sessionId, userId, snapshot.size());
        return snapshot.size();
    }

    /**
     * 发送单条消息，失败不抛。
     *
     * <p><b>为什么要 synchronized</b>：{@link WebSocketSession#sendMessage} 不是线程安全的。
     * 两个线程同时往同一连接发消息会抛
     * {@code IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]}。
     * 翻页广播 + 讨论区消息 + 心跳应答 + 错误提示都可能并发落到同一个 session 上，
     * 所以必须串行化。
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

    /** 仅供调试使用：当前登记的课堂数。 */
    public int trackedSessionCount() {
        return bySession.size();
    }

    /** 仅供调试使用：某课堂的连接快照（不可变）。 */
    Set<WebSocketSession> connectionsOf(Long sessionId, Long userId) {
        Map<Long, Set<WebSocketSession>> users = bySession.get(sessionId);
        if (users == null) {
            return Collections.emptySet();
        }
        Set<WebSocketSession> connections = users.get(userId);
        return connections == null ? Collections.emptySet() : Set.copyOf(connections);
    }
}
