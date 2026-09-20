package com.fridayclass.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 课堂事件的组装与下发（协议 v2）。
 *
 * <p>与 {@link PageBroadcaster} 的分工：那个只管 {@code page} / {@code ended} 两个
 * F003 依赖的既有事件，保持不动；**新增的下行事件全部走这里**——
 * 讨论区、在线名单、屏幕共享信令、暂停/踢人。硬塞进 PageBroadcaster 会让它的
 * 名字和内容对不上（一个叫「翻页广播器」的类在广播聊天消息）。
 *
 * <p>所有方法都不抛异常：一次广播失败绝不能把触发它的业务操作（发消息、进课堂）
 * 搞挂。失败只记日志。
 */
@Component
public class ClassBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ClassBroadcaster.class);

    private final SessionRegistry registry;
    private final ObjectMapper objectMapper;

    public ClassBroadcaster(SessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // ── 讨论区（M2） ───────────────────────────────────────────

    /** 广播一条新发言。 */
    public void chatNew(Long sessionId, Map<String, Object> message) {
        broadcast(sessionId, "chat.new", message);
    }

    /** 广播「某条发言被撤回」。其它客户端据此把它置灰。 */
    public void chatDeleted(Long sessionId, Long messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", messageId);
        broadcast(sessionId, "chat.deleted", payload);
    }

    // ── 在线名单（M1 / M2 / M6） ───────────────────────────────

    public void presenceJoin(Long sessionId, Long userId, String nickname, String role, int online) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("nickname", nickname);
        payload.put("role", role);
        payload.put("online", online);
        broadcast(sessionId, "presence.join", payload);
    }

    public void presenceLeave(Long sessionId, Long userId, String nickname, int online) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("nickname", nickname);
        payload.put("online", online);
        broadcast(sessionId, "presence.leave", payload);
    }

    // ── 屏幕共享（M1） ─────────────────────────────────────────

    public void streamStarted(Long sessionId, Long teacherId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teacherId", teacherId);
        broadcast(sessionId, "stream.started", payload);
    }

    public void streamStopped(Long sessionId) {
        broadcast(sessionId, "stream.stopped", new LinkedHashMap<>());
    }

    // ── 课堂状态（M6） ─────────────────────────────────────────

    public void classPaused(Long sessionId, Long byUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("by", byUserId);
        broadcast(sessionId, "class.paused", payload);
    }

    public void classResumed(Long sessionId, Long byUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("by", byUserId);
        broadcast(sessionId, "class.resumed", payload);
    }

    /** 通知某人已被踢出。服务端随后会关闭他的连接（见 SessionRegistry#closeUser）。 */
    public boolean kicked(Long sessionId, Long userId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        return sendToUser(sessionId, userId, "kicked", payload);
    }

    // ── WebRTC 信令（M1） ──────────────────────────────────────

    /**
     * 定向投递一条信令。
     *
     * <p>服务端<b>不解析</b> SDP / ICE，只按 {@code toUserId} 转发——
     * 保持与具体编解码方式解耦，将来换方案也不必改后端。
     *
     * @return 对方在线且已投递返回 true；不在线返回 false，由调用方决定是否丢弃
     */
    public boolean webrtcOffer(Long sessionId, Long toUserId, Long fromUserId, Object sdp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", fromUserId);
        payload.put("sdp", sdp);
        return sendToUser(sessionId, toUserId, "webrtc.offer", payload);
    }

    public boolean webrtcAnswer(Long sessionId, Long toUserId, Long fromUserId, Object sdp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", fromUserId);
        payload.put("sdp", sdp);
        return sendToUser(sessionId, toUserId, "webrtc.answer", payload);
    }

    public boolean webrtcIce(Long sessionId, Long toUserId, Long fromUserId, Object candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", fromUserId);
        payload.put("candidate", candidate);
        return sendToUser(sessionId, toUserId, "webrtc.ice", payload);
    }

    /** 老师端通知「某学生已就绪，可以发 offer 了」。 */
    public boolean webrtcReady(Long sessionId, Long toTeacherId, Long fromUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", fromUserId);
        return sendToUser(sessionId, toTeacherId, "webrtc.ready", payload);
    }

    // ── 底层 ───────────────────────────────────────────────────

    public void broadcast(Long sessionId, String type, Map<String, Object> payload) {
        send(sessionId, null, type, payload);
    }

    public boolean sendToUser(Long sessionId, Long userId, String type, Map<String, Object> payload) {
        return send(sessionId, userId, type, payload);
    }

    /**
     * @param userId 为 null 时广播给全课堂；非 null 时只投给该用户
     */
    private boolean send(Long sessionId, Long userId, String type, Map<String, Object> payload) {
        // sessionId 为 null 直接放弃。不是多余检查：SessionRegistry 内部是
        // ConcurrentHashMap，**不接受 null 键**，get(null) 会抛 NPE。
        // 而这里的调用方有的来自「拿到一条记录再取它的课堂」，
        // 关联万一为空时，宁可这条消息不发，也不能让 NPE 把业务操作炸掉。
        if (sessionId == null) {
            log.warn("class_broadcast_skipped reason=null_session type={}", type);
            return false;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(payload);
        // 统一带服务器时间：前端可以据此校正显示时间，也便于抓包时对齐排查
        message.put("serverTime", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            // 载荷全是简单类型，理论上不会失败；真失败也不能把业务操作搞挂
            log.error("class_broadcast_serialize_failed sessionId={} type={}", sessionId, type, ex);
            return false;
        }

        if (userId == null) {
            registry.broadcast(sessionId, json);
            return true;
        }
        return registry.sendToUser(sessionId, userId, json);
    }
}
