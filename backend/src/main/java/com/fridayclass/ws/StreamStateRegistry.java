package com.fridayclass.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这个课堂现在有没有共享流」的登记表。<b>单机内存实现，刻意不落库。</b>
 *
 * <p>为什么不建数据库列：共享流是<b>转瞬即逝的运行时状态</b>。
 * 媒体走的是老师与学生之间的点对点直连，服务端根本不经手画面；
 * 这里记的只是一个「老师说他开了」的标记。
 * 后端一重启，老师的页面连接也断了、流本来就没了——落库反而会留下
 * 「库里说在播、实际什么都没」的脏状态，还得专门写清理逻辑。
 *
 * <p>与 {@link SessionRegistry} 同理：多实例部署时要换成 Redis，
 * 否则 A 实例上的学生查不到 B 实例上老师开的流。
 */
@Component
public class StreamStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamStateRegistry.class);

    /** 课堂 ID → 开始共享的时间。不存在 = 当前没有流。 */
    private final Map<Long, LocalDateTime> startedAtBySession = new ConcurrentHashMap<>();

    /** 开始共享。重复调用只更新一次（幂等）。 */
    public LocalDateTime start(Long sessionId) {
        return startedAtBySession.computeIfAbsent(sessionId, key -> {
            log.info("stream_state_started sessionId={}", key);
            // 截到整秒，与 ClassSessionService / QaService 的取值口径一致。
            // 这里虽然不落库（不会出现 MySQL 四舍五入的不一致），
            // 但会把值直接返给前端，多带 7 位小数没有意义、还和别处的时间格式不一样。
            return LocalDateTime.now().withNano(0);
        });
    }

    /** 停止共享。返回之前是否确实在播。 */
    public boolean stop(Long sessionId) {
        boolean wasLive = startedAtBySession.remove(sessionId) != null;
        if (wasLive) {
            log.info("stream_state_stopped sessionId={}", sessionId);
        }
        return wasLive;
    }

    /** 是否正在共享。 */
    public boolean isLive(Long sessionId) {
        return startedAtBySession.containsKey(sessionId);
    }

    /** 开始时间；没有流时返回 null。 */
    public LocalDateTime startedAt(Long sessionId) {
        return startedAtBySession.get(sessionId);
    }

    /**
     * 课堂结束时清掉流标记。
     *
     * <p>下课了流就不该还挂着——否则学生重新进课堂会看到
     * 「有流」的提示却永远等不到画面。
     */
    public void clear(Long sessionId) {
        if (startedAtBySession.remove(sessionId) != null) {
            log.info("stream_state_cleared sessionId={}", sessionId);
        }
    }
}
