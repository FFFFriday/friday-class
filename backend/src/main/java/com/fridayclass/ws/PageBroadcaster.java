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
 * 翻页消息的组装与下发。
 *
 * <p>单独抽出来是为了让 Service 不必知道 WebSocket 的消息长什么样——
 * Service 只负责「页码变了」，这里负责「怎么告诉学生」。
 */
@Component
public class PageBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PageBroadcaster.class);

    private final SessionRegistry registry;
    private final ObjectMapper objectMapper;

    public PageBroadcaster(SessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 广播页码。**调用时机必须是数据库事务提交之后**（见 {@code ClassSessionService}），
     * 否则事务回滚了消息却已经发出去，学生会切到一个并不存在的页码。
     */
    public void broadcast(Long sessionId, int pageNo) {
        // LinkedHashMap 而不是 Map.of：保证字段顺序稳定，便于日志与抓包对比
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "page");
        message.put("pageNo", pageNo);
        message.put("serverTime", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());

        try {
            registry.broadcast(sessionId, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            // 值全是简单类型，理论上不会失败；真失败也不能把翻页本身搞挂，只记日志
            log.error("page_broadcast_serialize_failed sessionId={}", sessionId, ex);
        }
    }

    /**
     * 广播「下课」。
     *
     * <p>学生端收到后应当停止显示「等待老师翻页」，改显示「本节课已结束」——
     * 否则页面会一直停在最后一页，学生不知道是自己卡了还是课已经上完。
     *
     * <p>同样必须**在事务提交之后**调用（见 {@code ClassSessionService.end}）：
     * 事务回滚了却已经告诉学生「下课」，会把一节还在进行的课显示成已结束。
     */
    public void broadcastEnded(Long sessionId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ended");
        message.put("serverTime", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());

        try {
            registry.broadcast(sessionId, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            log.error("ended_broadcast_serialize_failed sessionId={}", sessionId, ex);
        }
    }
}
