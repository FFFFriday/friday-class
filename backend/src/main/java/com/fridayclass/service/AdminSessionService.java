package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.admin.BatchResult;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.enums.SessionStatus;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.SessionParticipantRepository;
import com.fridayclass.ws.ClassBroadcaster;
import com.fridayclass.ws.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 管理端的课堂管理（M6）。
 *
 * <h3>⚠️「暂停」到底能做什么，界面上必须写清楚</h3>
 * 媒体流是老师与学生<b>点对点直连</b>的，服务端<b>管不到画面</b>。
 * 点「暂停所有课堂」的实际效果是：
 * <table>
 *   <tr><th>会发生</th><th>不会发生</th></tr>
 *   <tr><td>状态改为 PAUSED</td><td>❌ 老师的画面不会被服务端切掉</td></tr>
 *   <tr><td>广播 class.paused，学生端盖遮罩</td><td>❌ 声音不会停</td></tr>
 *   <tr><td>讨论区与提问被禁用</td><td></td></tr>
 *   <tr><td>老师端收到提示，可自行停止共享</td><td></td></tr>
 * </table>
 * 要真正停画面，必须老师端配合。这是 P2P 架构的固有边界，
 * 文档与界面都要写明，免得「点了暂停画面还在」被当成 bug 反复排查。
 */
@Service
public class AdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final ClassSessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final ClassSessionService classSessionService;
    private final ClassPresenceService presenceService;
    private final SessionRegistry registry;
    private final ClassBroadcaster broadcaster;
    private final SessionParticipantService participantService;
    private final AdminAuditService auditService;

    public AdminSessionService(ClassSessionRepository sessionRepository,
                               SessionParticipantRepository participantRepository,
                               ClassSessionService classSessionService,
                               ClassPresenceService presenceService,
                               SessionRegistry registry,
                               ClassBroadcaster broadcaster,
                               SessionParticipantService participantService,
                               AdminAuditService auditService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.classSessionService = classSessionService;
        this.presenceService = presenceService;
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.participantService = participantService;
        this.auditService = auditService;
    }

    /** 全局课堂列表。状态、教师、关键字三个条件都可选。 */
    @Transactional(readOnly = true)
    public Page<ClassSession> list(String status, Long teacherId, String keyword, int page, int size) {
        SessionStatus parsed = parseStatusOrNull(status);
        String kw = blankToNull(keyword);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return sessionRepository.searchForAdmin(
                parsed != null, parsed,
                teacherId != null, teacherId,
                kw != null, kw == null ? "" : kw,
                PageRequest.of(Math.max(page, 1) - 1, safeSize));
    }

    @Transactional(readOnly = true)
    public ClassSession requireSession(Long id) {
        return classSessionService.requireSessionEntity(id);
    }

    /** 实时在线名单（数据源是内存登记表，不是数据库）。 */
    public List<ClassPresenceService.OnlineUser> online(Long sessionId) {
        return presenceService.onlineUsers(sessionId);
    }

    // ── 单个操作 ──────────────────────────────────────────────

    /** 强制下课。 */
    public void forceEnd(Long adminId, Long sessionId) {
        ClassSession session = requireSession(sessionId);
        String title = session.getTitle();

        classSessionService.forceEnd(sessionId, adminId, "ADMIN_FORCE");

        auditService.record(adminId, AdminAuditService.SESSION_FORCE_END,
                AdminAuditService.TARGET_SESSION, sessionId, "强制下课：" + title);
    }

    public void pause(Long adminId, Long sessionId) {
        ClassSession session = requireSession(sessionId);
        String title = session.getTitle();

        classSessionService.pause(sessionId, adminId);

        auditService.record(adminId, AdminAuditService.SESSION_PAUSE,
                AdminAuditService.TARGET_SESSION, sessionId, "暂停课堂：" + title);
    }

    public void resume(Long adminId, Long sessionId) {
        ClassSession session = requireSession(sessionId);
        String title = session.getTitle();

        classSessionService.resume(sessionId, adminId);

        auditService.record(adminId, AdminAuditService.SESSION_RESUME,
                AdminAuditService.TARGET_SESSION, sessionId, "恢复课堂：" + title);
    }

    /**
     * 踢人：先告知、再关连接。
     *
     * <p>顺序不能反。<b>先关连接的话，那条「你被踢了」的提示发不出去</b>，
     * 学生端只会看到连接莫名其妙断了，然后自动重连回来——
     * 管理员会以为踢人没生效，反复点。先发消息再关，学生才知道发生了什么。
     */
    public void kick(Long adminId, Long sessionId, Long userId, String reason) {
        String finalReason = (reason == null || reason.isBlank()) ? "被管理员移出课堂" : reason;

        boolean notified = broadcaster.kicked(sessionId, userId, finalReason);
        int closed = registry.closeUser(sessionId, userId, "kicked");

        try {
            participantService.leave(sessionId, userId);
        } catch (Exception ex) {
            log.debug("kick_participant_leave_failed sessionId={} userId={} reason={}",
                    sessionId, userId, ex.getMessage());
        }

        auditService.record(adminId, AdminAuditService.SESSION_KICK,
                AdminAuditService.TARGET_SESSION, sessionId,
                "移出学生 userId=" + userId + "（通知" + (notified ? "已送达" : "未送达")
                        + "，关闭连接 " + closed + " 条）：" + finalReason);

        log.info("admin_kick adminId={} sessionId={} userId={} closed={}",
                adminId, sessionId, userId, closed);
    }

    // ── 批量操作 ──────────────────────────────────────────────

    /** 暂停所有<b>进行中</b>（LIVE）的课堂。 */
    public BatchResult pauseAll(Long adminId) {
        return forEachLive(adminId, AdminAuditService.SESSION_PAUSE_ALL, "批量暂停",
                id -> classSessionService.pause(id, adminId));
    }

    /** 恢复所有已暂停的课堂。 */
    public BatchResult resumeAll(Long adminId) {
        return forEachByStatus(adminId, SessionStatus.PAUSED,
                AdminAuditService.SESSION_RESUME_ALL, "批量恢复",
                id -> classSessionService.resume(id, adminId));
    }

    /** 强制结束所有进行中的课堂。 */
    public BatchResult endAll(Long adminId) {
        return forEachLive(adminId, AdminAuditService.SESSION_END_ALL, "批量下课",
                id -> classSessionService.forceEnd(id, adminId, "ADMIN_FORCE"));
    }

    private BatchResult forEachLive(Long adminId, String action, String label, Consumer<Long> op) {
        return forEachByStatus(adminId, SessionStatus.LIVE, action, label, op);
    }

    /**
     * 逐个执行、逐个记账。
     *
     * <p><b>不在一个事务里。</b>批量操作里某一个失败（比如那节课刚好被别处结课了），
     * 不该把其他成功的都回滚掉——管理员看到的会是「点了没反应」，
     * 而且完全不知道哪几个生效了。所以每个都独立提交，最后汇总成败。
     */
    private BatchResult forEachByStatus(Long adminId, SessionStatus status,
                                        String action, String label, Consumer<Long> op) {
        List<ClassSession> targets = sessionRepository.findByStatus(status);
        List<BatchResult.Failure> failures = new ArrayList<>();
        int succeeded = 0;

        for (ClassSession session : targets) {
            try {
                op.accept(session.getId());
                succeeded++;
            } catch (Exception ex) {
                String reason = ex instanceof BusinessException
                        ? ex.getMessage()
                        : "操作失败：" + ex.getClass().getSimpleName();
                failures.add(new BatchResult.Failure(session.getId(), session.getTitle(), reason));
                log.warn("batch_op_failed action={} sessionId={} reason={}", action, session.getId(), reason);
            }
        }

        auditService.record(adminId, action, AdminAuditService.TARGET_SESSION, null,
                label + "：共 " + targets.size() + " 个，成功 " + succeeded
                        + "，失败 " + failures.size());

        log.info("batch_op action={} adminId={} total={} ok={} failed={}",
                action, adminId, targets.size(), succeeded, failures.size());

        return BatchResult.of(targets.size(), succeeded, failures);
    }

    private SessionStatus parseStatusOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return SessionStatus.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
