package com.fridayclass.service;

import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.SessionParticipant;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.SessionParticipantRepository;
import com.fridayclass.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 课堂参与者记录的维护。
 *
 * <p><b>与实时在线名单的分工要说清楚</b>：
 * <ul>
 *   <li><b>谁现在在线</b> → 看 {@code SessionRegistry} 的内存态。<b>实时、准确</b>，
 *       因为它是连接本身的登记表，断开即消失。</li>
 *   <li><b>谁来过这节课</b> → 看本服务维护的 {@code session_participant} 表。
 *       <b>用于出席统计与课堂记录</b>，不用于实时判断。</li>
 * </ul>
 * 为什么不合并成一个：浏览器崩溃、拔网线、断电都不会发出「离开」消息，
 * 数据库里那行会永远停在 {@code left_at = NULL}。拿它当在线依据，
 * 在线人数只会越来越多、永远不会减。所以实时用内存、留痕用数据库。
 */
@Service
public class SessionParticipantService {

    private static final Logger log = LoggerFactory.getLogger(SessionParticipantService.class);

    private final SessionParticipantRepository participantRepository;
    private final ClassSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public SessionParticipantService(SessionParticipantRepository participantRepository,
                                     ClassSessionRepository sessionRepository,
                                     UserRepository userRepository) {
        this.participantRepository = participantRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /**
     * 记录「进入课堂」。<b>幂等</b>——一个用户在一节课里只有一行。
     *
     * <p>重复进入（刷新页面、断线重连）只把 {@code left_at} 清回 NULL 并更新心跳，
     * 不新增行、也不改 {@code joined_at}（首次进入的时间才是「出席时间」）。
     */
    @Transactional
    public void join(Long sessionId, Long userId, Role role) {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        SessionParticipant participant = participantRepository
                .findBySessionIdAndUserId(sessionId, userId)
                .orElse(null);

        if (participant == null) {
            SessionParticipant created = new SessionParticipant();
            created.setSession(sessionRepository.getReferenceById(sessionId));
            created.setUser(userRepository.getReferenceById(userId));
            created.setRole(role);
            created.setLastSeenAt(now);
            participantRepository.save(created);
            log.debug("participant_joined sessionId={} userId={} role={}", sessionId, userId, role);
            return;
        }

        // 重新进入：清掉离开标记，刷新心跳。joined_at 保持首次的值不动。
        participant.setLeftAt(null);
        participant.setLastSeenAt(now);
        participantRepository.save(participant);
        log.debug("participant_rejoined sessionId={} userId={}", sessionId, userId);
    }

    /**
     * 记录「离开课堂」。
     *
     * <p>只在<b>该用户的最后一条连接</b>断开时调用（多标签页时先关的那页不算离开），
     * 判定由 {@code PageWebSocketHandler} 负责。
     */
    @Transactional
    public void leave(Long sessionId, Long userId) {
        participantRepository.findBySessionIdAndUserId(sessionId, userId).ifPresent(participant -> {
            participant.setLeftAt(LocalDateTime.now().withNano(0));
            participantRepository.save(participant);
            log.debug("participant_left sessionId={} userId={}", sessionId, userId);
        });
    }

    /** 刷新心跳时间。消息到达时顺手调用，供掉线判定使用。 */
    @Transactional
    public void touch(Long sessionId, Long userId) {
        participantRepository.findBySessionIdAndUserId(sessionId, userId).ifPresent(participant -> {
            participant.setLastSeenAt(LocalDateTime.now().withNano(0));
            participantRepository.save(participant);
        });
    }

    /**
     * 取课堂的授课教师 ID。给 WebSocket 转发 {@code webrtc.ready} 用
     * （学生就绪时要通知老师开始协商，而消息里不带老师是谁）。
     */
    @Transactional(readOnly = true)
    public Long teacherIdOf(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .map(ClassSession::getTeacher)
                .map(teacher -> teacher == null ? null : teacher.getId())
                .orElse(null);
    }
}
