package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.enums.Role;
import com.fridayclass.enums.SessionVisibility;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.SessionAudienceRepository;
import com.fridayclass.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 课堂可见性判定 —— <b>全项目唯一的那一处判断</b>。
 *
 * <h3>为什么必须收口，而不是各接口自己写 if</h3>
 *
 * 问题点 7 的要求是「其他班级的学生、没被选中上课的学生，不能看到其他的课堂」。
 * 只把课堂**列表**过滤掉是远远不够的 —— 学生只要把 URL 里的 id 改一下，
 * 就能拿到别人的课堂详情、翻页状态、屏幕共享信令、讨论区、问答记录。
 * 这类「列表挡了、详情没挡」的漏洞几乎每一处都会漏，只要判定散落在
 * 8 个接口里各写一遍。
 *
 * 所以口径只有一句话，写在 {@link #canSee}：
 *
 * <blockquote>
 *   <b>公开课堂（{@code visibility = PUBLIC}）谁都能看；
 *   限定课堂只有 {@code session_audience} 名单里的人能看。</b>
 * </blockquote>
 *
 * 所有需要挡学生的接口都调 {@link #requireStudentAccess}，不自己写条件。
 *
 * <h3>边界</h3>
 *
 * 本服务**只约束学生**。教师与管理员不受可见性限制 ——
 * 「这节课是不是我的」是另一套判定（{@code ClassSessionService} 里的
 * {@code teacherId} 比对），不要混进来。
 */
@Service
public class SessionAccessService {

    private static final Logger log = LoggerFactory.getLogger(SessionAccessService.class);

    private final ClassSessionRepository sessionRepository;
    private final SessionAudienceRepository audienceRepository;

    public SessionAccessService(ClassSessionRepository sessionRepository,
                                SessionAudienceRepository audienceRepository) {
        this.sessionRepository = sessionRepository;
        this.audienceRepository = audienceRepository;
    }

    /** 调用者是不是学生（唯一受可见性约束的角色）。 */
    public static boolean isStudent(UserPrincipal principal) {
        return principal != null && Role.STUDENT.name().equals(principal.getRole());
    }

    /**
     * 这节课这个学生能不能看。<b>全项目唯一的可见性口径。</b>
     *
     * <p>先看总开关再看名单，顺序不能反：公开课不需要查名单，
     * 而名单查询是唯一键直接命中，成本本来就低。
     */
    @Transactional(readOnly = true)
    public boolean canSee(ClassSession session, Long userId) {
        if (session.getVisibility() != SessionVisibility.RESTRICTED) {
            return true;
        }
        return audienceRepository.existsBySessionIdAndUserId(session.getId(), userId);
    }

    /**
     * 把不该看到这节课的学生挡在门外；其他人原样放行。
     *
     * @throws BusinessException 404 课堂不存在（不区分「不存在」与「无权看」以外的情形，
     *                           避免用错误信息泄露「有这么一节课」）；403 无权访问
     */
    @Transactional(readOnly = true)
    public void requireStudentAccess(Long sessionId, UserPrincipal principal) {
        if (!isStudent(principal)) {
            return;
        }
        ClassSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));
        if (!canSee(session, principal.getId())) {
            // 记一条日志：正常使用不会走到这里，出现即代表有人在试 URL。
            // 只记 id，不记用户名——日志本身也不该成为泄露渠道。
            log.warn("session_access_denied sessionId={} userId={}", sessionId, principal.getId());
            throw new BusinessException(403, "无权访问这节课");
        }
    }

    /** 按 id 取课堂，同时校验学生可见性。取不到就 404。 */
    @Transactional(readOnly = true)
    public ClassSession requireVisibleSession(Long sessionId, UserPrincipal principal) {
        requireStudentAccess(sessionId, principal);
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));
    }

    /** 同上但返回 Optional —— 给「取不到就走别的分支」的调用点用。 */
    @Transactional(readOnly = true)
    public Optional<ClassSession> findVisibleSession(Long sessionId, UserPrincipal principal) {
        requireStudentAccess(sessionId, principal);
        return sessionRepository.findById(sessionId);
    }
}
