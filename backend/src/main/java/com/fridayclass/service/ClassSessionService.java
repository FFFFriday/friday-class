package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.SessionCreateRequest;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.User;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.enums.SessionStatus;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.ws.PageBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 课堂业务：开课、查课、翻页。
 *
 * <h3>为什么翻页用 TransactionTemplate 而不是 @Transactional</h3>
 * 翻页要做两件事：**写库** + **广播**，而且顺序不能反——事务回滚了消息却已经发出去的话，
 * 学生会切到一个数据库里并不存在的页码。用 {@code @Transactional} 很难表达
 * 「提交之后再执行」；用 {@code TransactionTemplate} 则一目了然：
 * {@code execute()} 返回即代表已提交，之后才广播。
 */
@Service
public class ClassSessionService {

    private static final Logger log = LoggerFactory.getLogger(ClassSessionService.class);

    /** 能开课的课件状态：页面已就绪（CONVERTED）或已解析完成（PARSED）。 */
    private static final Set<CoursewareStatus> READY_STATUS =
            EnumSet.of(CoursewareStatus.CONVERTED, CoursewareStatus.PARSED);

    /** 与 courseware.name / class_session.title 的列长度一致。 */
    private static final int MAX_TITLE_LENGTH = 200;

    private final ClassSessionRepository sessionRepository;
    private final CoursewareRepository coursewareRepository;
    private final UserRepository userRepository;
    private final PageBroadcaster pageBroadcaster;
    private final TransactionTemplate transactionTemplate;

    public ClassSessionService(ClassSessionRepository sessionRepository,
                               CoursewareRepository coursewareRepository,
                               UserRepository userRepository,
                               PageBroadcaster pageBroadcaster,
                               PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.coursewareRepository = coursewareRepository;
        this.userRepository = userRepository;
        this.pageBroadcaster = pageBroadcaster;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 开课。初始状态 {@link SessionStatus#NOT_STARTED}，首次翻页时自动转为 LIVE。 */
    @Transactional
    public SessionResponse create(Long teacherId, SessionCreateRequest request) {
        Courseware courseware = coursewareRepository.findById(request.coursewareId())
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));

        if (!READY_STATUS.contains(courseware.getStatus())) {
            throw new BusinessException("课件尚未解析完成，无法开课（当前状态："
                    + courseware.getStatus().name() + "）");
        }

        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new BusinessException(401, "登录已失效，请重新登录"));

        ClassSession session = new ClassSession();
        session.setCourseware(courseware);
        session.setTeacher(teacher);
        session.setTitle(resolveTitle(request.title(), courseware.getName()));
        session.setStatus(SessionStatus.NOT_STARTED);
        session = sessionRepository.save(session);

        log.info("session_created id={} coursewareId={} teacher={}",
                session.getId(), courseware.getId(), teacher.getUsername());

        return SessionResponse.from(session);
    }

    /** 课堂详情。不存在（含已软删除）返回 404。 */
    @Transactional(readOnly = true)
    public SessionResponse detail(Long sessionId) {
        return SessionResponse.from(requireSession(sessionId));
    }

    /**
     * 正在直播的课堂列表。
     *
     * <p>这是学生**唯一的入口**——没有它，学生只能靠老师私下发链接，
     * 门户上看不到「现在有课在讲」。按开课时间倒序，最近开的排最前。
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listActive() {
        return sessionRepository.findByStatusWithDetail(SessionStatus.LIVE)
                .stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * 翻页：**先落库、提交后再广播**。
     *
     * @param teacherId 当前登录教师；必须是本课堂的授课教师，否则 403
     * @return 翻页后的课堂详情（前端不必再补一次 GET）
     */
    public SessionResponse updateCurrentPage(Long sessionId, Long teacherId, int pageNo) {
        SessionResponse updated = transactionTemplate.execute(
                status -> changePageInTransaction(sessionId, teacherId, pageNo));

        // 执行到这里说明事务已提交。广播放在事务外是刻意的，理由见类注释。
        pageBroadcaster.broadcast(sessionId, pageNo);
        return updated;
    }

    private SessionResponse changePageInTransaction(Long sessionId, Long teacherId, int pageNo) {
        ClassSession session = requireSession(sessionId);

        User teacher = session.getTeacher();
        if (teacher == null || !teacher.getId().equals(teacherId)) {
            // 教师可以开课，但不能翻别人的课堂
            throw new BusinessException(403, "这不是你的课堂");
        }
        if (session.getStatus() == SessionStatus.ENDED) {
            throw new BusinessException("课堂已结束，无法翻页");
        }

        Courseware courseware = session.getCourseware();
        Integer totalPages = courseware == null ? null : courseware.getPageCount();
        if (totalPages != null && totalPages > 0 && pageNo > totalPages) {
            throw new BusinessException("页码超出范围：本课件共 " + totalPages + " 页");
        }

        // 首次翻页即开课：省掉一个「开始上课」接口，也少一个前端忘了调的状态。
        if (session.getStatus() == SessionStatus.NOT_STARTED) {
            session.setStatus(SessionStatus.LIVE);
            session.setStartedAt(LocalDateTime.now());
        }
        session.setCurrentPage(pageNo);

        // 实体在事务内是托管态，这里显式 save 只是为了读起来明确；
        // merge 对托管实体返回同一个实例，不会产生游离副本。
        session = sessionRepository.save(session);

        log.info("session_page_changed id={} pageNo={} teacherId={}", sessionId, pageNo, teacherId);

        return SessionResponse.from(session);
    }

    /**
     * 取课堂，并 JOIN FETCH 出课件与教师。
     *
     * <p>必须走 {@code findDetailById} 而不是 {@code findById}：组装 DTO 要读
     * {@code courseware.name} 与 {@code teacher.nickname}，在
     * {@code open-in-view=false} 下用 {@code findById} 会在事务外抛
     * {@code LazyInitializationException}；而 {@code Courseware} 上带
     * {@code @SQLRestriction("deleted = 0")}，课件被软删除时懒加载代理还会抛
     * {@code ObjectRetrievalFailureException}。
     */
    private ClassSession requireSession(Long sessionId) {
        return sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));
    }

    /** 课堂名缺省用课件名；再兜一层默认值，避免出现空标题。 */
    private String resolveTitle(String title, String coursewareName) {
        String value = title == null ? null : title.strip();
        if (value == null || value.isEmpty()) {
            value = (coursewareName == null || coursewareName.isBlank()) ? "未命名课堂" : coursewareName;
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            // 按码点截断，避免把 emoji 等代理对切成半个字符
            int end = value.offsetByCodePoints(0,
                    Math.min(value.codePointCount(0, value.length()), MAX_TITLE_LENGTH));
            value = value.substring(0, end);
        }
        return value;
    }
}
