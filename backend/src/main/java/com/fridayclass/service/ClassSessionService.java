package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.MySessionResponse;
import com.fridayclass.dto.SessionCreateRequest;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.dto.StreamStateResponse;
import com.fridayclass.entity.ClassGroup;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.SessionAudience;
import com.fridayclass.entity.SessionClassGroup;
import com.fridayclass.entity.User;
import com.fridayclass.enums.AudienceSource;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.enums.Role;
import com.fridayclass.enums.SessionStatus;
import com.fridayclass.enums.SessionVisibility;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CourseSummaryRepository;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.SessionAudienceRepository;
import com.fridayclass.repository.SessionClassGroupRepository;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.security.UserPrincipal;
import com.fridayclass.ws.ClassBroadcaster;
import com.fridayclass.ws.PageBroadcaster;
import com.fridayclass.ws.StreamStateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SessionAudienceRepository audienceRepository;
    private final SessionClassGroupRepository sessionGroupRepository;
    private final CourseSummaryRepository summaryRepository;
    private final ClassGroupService classGroupService;
    private final PageBroadcaster pageBroadcaster;
    private final ClassBroadcaster classBroadcaster;
    private final StreamStateRegistry streamStateRegistry;
    private final TransactionTemplate transactionTemplate;

    public ClassSessionService(ClassSessionRepository sessionRepository,
                               CoursewareRepository coursewareRepository,
                               UserRepository userRepository,
                               SessionAudienceRepository audienceRepository,
                               SessionClassGroupRepository sessionGroupRepository,
                               CourseSummaryRepository summaryRepository,
                               ClassGroupService classGroupService,
                               PageBroadcaster pageBroadcaster,
                               ClassBroadcaster classBroadcaster,
                               StreamStateRegistry streamStateRegistry,
                               PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.coursewareRepository = coursewareRepository;
        this.userRepository = userRepository;
        this.audienceRepository = audienceRepository;
        this.sessionGroupRepository = sessionGroupRepository;
        this.summaryRepository = summaryRepository;
        this.classGroupService = classGroupService;
        this.pageBroadcaster = pageBroadcaster;
        this.classBroadcaster = classBroadcaster;
        this.streamStateRegistry = streamStateRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 开课。初始状态 {@link SessionStatus#NOT_STARTED}，首次翻页时自动转为 LIVE。
     *
     * <p>P7 起多了「授课对象」：选班级（可多选=合班）、勾选学生、或公开课，三者必须显式表态。
     * 开课**同一事务内**生成 {@code session_audience} 快照 —— 这是之后所有可见性判定的唯一依据。
     */
    @Transactional
    public SessionResponse create(Long teacherId, SessionCreateRequest request) {
        // ① 授课对象校验。放在最前面：不满足就直接拒，不做任何写操作。
        List<Long> groupIds = distinctIds(request.classGroupIds());
        List<Long> studentIds = distinctIds(request.studentIds());
        boolean isPublic = request.visibility() == SessionVisibility.PUBLIC;

        if (isPublic && (!groupIds.isEmpty() || !studentIds.isEmpty())) {
            throw new BusinessException("公开课不能再指定班级或学生：两者只能选一个");
        }
        if (!isPublic && groupIds.isEmpty() && studentIds.isEmpty()) {
            throw new BusinessException("请先选择授课对象：选班级、勾选学生，或设为公开课");
        }

        Courseware courseware = coursewareRepository.findById(request.coursewareId())
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));

        if (!READY_STATUS.contains(courseware.getStatus())) {
            throw new BusinessException("课件尚未解析完成，无法开课（当前状态："
                    + courseware.getStatus().name() + "）");
        }

        // 幂等：这位老师对这份课件已经有未结束的课堂，就把它原样还回去，不再新建。
        //
        // 为什么必须这样：老师每点一次「开始上课」就多一节课，而他只能停在最新那节，
        // 前面那节永远不会被结束——更糟的是老师退出控制台后，旧课既不在
        // 「正在直播」里（因为还没翻过页，状态还是 NOT_STARTED），也没有入口回去，
        // 就彻底卡死了。这是 F003 反馈 #3 的根因。
        ClassSession existing = sessionRepository
                .findActiveByTeacherAndCourseware(teacherId, courseware.getId())
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            log.info("session_create_reused id={} coursewareId={} teacherId={} status={}",
                    existing.getId(), courseware.getId(), teacherId, existing.getStatus());
            return SessionResponse.from(existing);
        }

        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new BusinessException(401, "登录已失效，请重新登录"));

        ClassSession session = new ClassSession();
        session.setCourseware(courseware);
        session.setTeacher(teacher);
        session.setTitle(resolveTitle(request.title(), courseware.getName()));
        session.setStatus(SessionStatus.NOT_STARTED);
        session.setVisibility(isPublic ? SessionVisibility.PUBLIC : SessionVisibility.RESTRICTED);
        session = sessionRepository.save(session);

        // ③ 生成听课名单快照 + 记录面向的班级。公开课不需要名单（谁都能看）。
        int audienceCount = 0;
        if (!isPublic) {
            audienceCount = writeAudienceSnapshot(session, teacherId, groupIds, studentIds);
        }

        log.info("session_created id={} coursewareId={} teacher={} visibility={} audience={} groups={}",
                session.getId(), courseware.getId(), teacher.getUsername(),
                session.getVisibility(), audienceCount, groupIds.size());

        return SessionResponse.from(session);
    }

    // ── 授课对象（P7） ─────────────────────────────────────────

    /**
     * 写入开课快照：{@code session_audience}（谁能看）+ {@code session_class_group}（面向哪些班）。
     *
     * <p>合班在这里被**摊平成并集**：选中的每个班取全体成员，合并去重后逐行写入。
     * 所以「合班」在授权层面根本不是特例 —— 之后无论查谁能不能看，
     * 都只是在这一张表上做一次唯一键命中。
     *
     * @return 实际写入的名单人数
     */
    private int writeAudienceSnapshot(ClassSession session, Long teacherId,
                                      List<Long> groupIds, List<Long> studentIds) {
        // 这一步内部会校验这些班都属于这位教师（横向越权防护）
        Set<Long> fromGroups = classGroupService.resolveAudienceUserIds(groupIds, teacherId);
        Set<Long> manual = requireStudents(studentIds);

        List<SessionAudience> rows = new ArrayList<>();
        for (Long userId : fromGroups) {
            rows.add(audienceRow(session, userId, AudienceSource.CLASS));
        }
        for (Long userId : manual) {
            // 已被班级带进来的人不重复占行 —— 唯一键也会挡住，
            // 但先在内存里去重可以避免拿约束违例当控制流
            if (!fromGroups.contains(userId)) {
                rows.add(audienceRow(session, userId, AudienceSource.MANUAL));
            }
        }
        audienceRepository.saveAll(rows);

        // 记录这节课面向哪些班（仅供展示与「按班查课」，不参与授权）
        List<SessionClassGroup> links = new ArrayList<>();
        for (ClassGroup group : classGroupService.findAllByIds(groupIds)) {
            SessionClassGroup link = new SessionClassGroup();
            link.setSession(session);
            link.setClassGroup(group);
            links.add(link);
        }
        sessionGroupRepository.saveAll(links);

        return rows.size();
    }

    /**
     * 把请求里的学生 ID 校验成「真的能听课的学生」。
     *
     * <p><b>不合格的一律拒绝，而不是静默丢弃。</b> 静默丢弃的话，老师明明勾了张三，
     * 上课时张三却进不来，而且没有任何提示 —— 这种「看起来成功了」的失败最难查。
     * 直接报错说清楚有几个不合格，比事后排查便宜得多。
     *
     * <p>过滤条件与 {@code ClassGroupService.addMembers} 完全一致：
     * 未删除、未禁用、且角色是学生。**不能把教师或管理员加进听课名单**，
     * 否则班级名单会成为一条提权路径。
     */
    private Set<Long> requireStudents(List<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, User> found = new HashMap<>();
        userRepository.findAllById(studentIds).forEach(u -> found.put(u.getId(), u));

        Set<Long> valid = new LinkedHashSet<>();
        for (Long id : studentIds) {
            User user = found.get(id);
            if (user != null
                    && !Boolean.TRUE.equals(user.getDeleted())
                    && !Boolean.TRUE.equals(user.getDisabled())
                    && user.getRole() == Role.STUDENT) {
                valid.add(id);
            }
        }
        if (valid.size() != studentIds.size()) {
            throw new BusinessException("选中的学生里有 "
                    + (studentIds.size() - valid.size())
                    + " 个不是有效学生账号（可能已删除、已禁用或不是学生）");
        }
        return valid;
    }

    private SessionAudience audienceRow(ClassSession session, Long userId, AudienceSource source) {
        SessionAudience row = new SessionAudience();
        row.setSession(session);
        row.setUser(userRepository.getReferenceById(userId));
        row.setSource(source);
        return row;
    }

    /** 去掉 null 并保序去重。前端传重复 id 是常事，不该变成两次插入。 */
    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(ids)).stream().filter(java.util.Objects::nonNull).toList();
    }

    /** 课堂详情。不存在（含已软删除）返回 404。 */
    @Transactional(readOnly = true)
    public SessionResponse detail(Long sessionId) {
        return SessionResponse.from(requireSession(sessionId));
    }

    /**
     * 正在直播的课堂列表。
     *
     * <p>这是学生发现「现在有课在讲」的入口。按开课时间倒序，最近开的排最前。
     *
     * <p><b>学生看到的必须是过滤后的结果（问题点 7）</b>：只在 SQL 里筛，
     * 不把全量捞出来再在内存里删。后者会让不该给这个学生看的课堂
     * （含标题、教师名）先进入应用进程，判定一旦写错就是全量泄露。
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listActive(UserPrincipal principal) {
        List<ClassSession> sessions = SessionAccessService.isStudent(principal)
                ? sessionRepository.findByStatusVisibleTo(SessionStatus.LIVE, principal.getId())
                : sessionRepository.findByStatusWithDetail(SessionStatus.LIVE);
        return sessions.stream().map(SessionResponse::from).toList();
    }

    /**
     * 学生端「我的课堂」（问题点 5）。
     *
     * <p>返回他能看到的**全部**课堂：公开课 + {@code session_audience} 名单里的课。
     * 进行中的排在前面（可以直接进去），已结束的排在后面（点进回顾页）。
     *
     * <p>⚠ 由于历史课堂被统一设为 {@code PUBLIC}（Friday 决策 3），
     * 学生会看到全部历史课。这是那条决策的直接结果，不是 bug ——
     * 若希望「我的课堂」只显示真正点过名的课，改
     * {@code ClassSessionRepository.findMySessions} 的 where 条件即可。
     */
    @Transactional(readOnly = true)
    public List<MySessionResponse> mySessions(UserPrincipal principal) {
        if (!SessionAccessService.isStudent(principal)) {
            throw new BusinessException(403, "「我的课堂」是学生端接口");
        }

        List<ClassSession> sessions = sessionRepository.findMySessions(principal.getId());
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = sessions.stream().map(ClassSession::getId).toList();

        // 下面两次都是批量查，不是逐条查 —— 否则一页 20 节课会变成 40 次 SQL
        Map<Long, List<String>> groupNames = new HashMap<>();
        for (SessionClassGroup link : sessionGroupRepository.findBySessionIdsWithGroup(ids)) {
            groupNames.computeIfAbsent(link.getSession().getId(), k -> new ArrayList<>())
                    .add(link.getClassGroup().getName());
        }
        Set<Long> withSummary = new HashSet<>(summaryRepository.findSessionIdsWithSummary(ids));

        return sessions.stream()
                .map(s -> MySessionResponse.from(s,
                        groupNames.getOrDefault(s.getId(), List.of()),
                        withSummary.contains(s.getId())))
                .toList();
    }

    /**
     * 当前教师名下**所有未结束**的课堂（教师首页「我的课堂」+ 课件详情页的返回入口）。
     *
     * <p>与 {@link #listActive()} 的区别是关键：那个只查 {@code LIVE}，因为学生只关心
     * 「现在有没有课在讲」；而教师必须看到自己**未开始**（{@code NOT_STARTED}）的课堂，
     * 否则开完课还没翻页就退出，就再也回不去了。
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listMine(Long teacherId) {
        return sessionRepository.findMineWithDetail(teacherId)
                .stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * 我教过的全部课堂，**含已结束的**（教师首页「我的课堂」）。
     *
     * <p>与 {@link #listMine} 的分工：那个答「回到我正在上的课」，
     * 这个答「我上过什么、哪些可以回顾」。已结束的课在这里才有入口
     * —— 在此之前，老师一离开直播页就再也找不到回顾页了（问题点 9 的根因）。
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listTaught(Long teacherId) {
        return sessionRepository.findTaughtWithDetail(teacherId)
                .stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * 下课：置为 {@code ENDED} 并广播。
     *
     * <p>幂等——重复点（或网络重试导致重复请求）不会报错，直接返回当前状态。
     * 已结束的课堂再被广播一次「下课」是安全的，学生端本来就显示已结束。
     *
     * <p>与翻页同理用 {@code TransactionTemplate}：**先落库、提交后再广播**。
     */
    public SessionResponse end(Long sessionId, Long teacherId) {
        SessionResponse result = transactionTemplate.execute(
                status -> endInTransaction(sessionId, teacherId));

        // 下课了就不该还挂着「有共享流」的标记：学生重新进课堂会看到
        // 「老师正在共享」的提示，然后永远等不到画面。
        // 放在广播之前，保证 stream.stopped 与 ended 的先后语义一致。
        if (streamStateRegistry.stop(sessionId)) {
            classBroadcaster.streamStopped(sessionId);
        }

        pageBroadcaster.broadcastEnded(sessionId);
        return result;
    }

    /**
     * 强制下课（管理端）。与 {@link #end} 的区别：
     * <ul>
     *   <li>不校验「是不是你的课堂」——管理员本来就能结任何人的课；</li>
     *   <li>记录 {@code endedBy} 与 {@code endReason=ADMIN_FORCE}，让「这课是谁结的」可追溯。</li>
     * </ul>
     */
    public SessionResponse forceEnd(Long sessionId, Long adminId, String reason) {
        SessionResponse result = transactionTemplate.execute(status -> {
            ClassSession session = requireSession(sessionId);
            if (session.getStatus() != SessionStatus.ENDED) {
                session.setStatus(SessionStatus.ENDED);
                session.setEndedAt(nowToSecond());
                session.setEndedBy(adminId);
                session.setEndReason(reason == null || reason.isBlank() ? "ADMIN_FORCE" : reason);
                session = sessionRepository.save(session);
                log.info("session_force_ended id={} by={}", sessionId, adminId);
            }
            return SessionResponse.from(session);
        });

        // 事务提交后再广播与清理（与 end 同一套顺序）
        if (streamStateRegistry.stop(sessionId)) {
            classBroadcaster.streamStopped(sessionId);
        }
        pageBroadcaster.broadcastEnded(sessionId);
        return result;
    }

    /** 取课堂实体（管理端批量操作用）。 */
    @Transactional(readOnly = true)
    public ClassSession requireSessionEntity(Long sessionId) {
        return requireSession(sessionId);
    }

    // ── 暂停 / 恢复（M6 管理端） ───────────────────────────────

    /**
     * 暂停课堂：置为 {@code PAUSED} + 广播 {@code class.paused}。
     *
     * <p><b>服务端切不断画面。</b>媒体流是老师与学生点对点直连的，
     * 服务端手里的开关只有三个：改状态、广播事件、让学生端盖遮罩。
     * 要真正停掉画面，得老师端收到通知后自己停止共享。
     * 这是 P2P 架构的固有边界，不是缺陷——界面上必须写明，
     * 否则「点了暂停画面还在」会被当成 bug 反复排查。
     *
     * <p>已结束的课堂不能暂停（它本来就没在跑）。
     *
     * @return 暂停后的课堂；本来就已经暂停时原样返回（幂等）
     */
    public SessionResponse pause(Long sessionId, Long operatorId) {
        SessionResponse result = transactionTemplate.execute(
                status -> changePausedInTransaction(sessionId, operatorId, true));

        classBroadcaster.classPaused(sessionId, operatorId);
        return result;
    }

    /** 恢复课堂：{@code PAUSED} → {@code LIVE}，清空 pausedAt，广播 {@code class.resumed}。 */
    public SessionResponse resume(Long sessionId, Long operatorId) {
        SessionResponse result = transactionTemplate.execute(
                status -> changePausedInTransaction(sessionId, operatorId, false));

        classBroadcaster.classResumed(sessionId, operatorId);
        return result;
    }

    /**
     * @param pause true = 暂停，false = 恢复
     */
    private SessionResponse changePausedInTransaction(Long sessionId, Long operatorId, boolean pause) {
        ClassSession session = requireSession(sessionId);

        if (session.getStatus() == SessionStatus.ENDED) {
            throw new BusinessException("课堂已结束，无法" + (pause ? "暂停" : "恢复"));
        }

        if (pause) {
            if (session.getStatus() != SessionStatus.PAUSED) {
                // 连 NOT_STARTED 也一起置为 PAUSED：学生可能已经进了课堂（WS 连着），
                // 状态保持 NOT_STARTED 的话前端拿不到「已暂停」这个信号
                session.setStatus(SessionStatus.PAUSED);
                session.setPausedAt(nowToSecond());
                session = sessionRepository.save(session);
            }
            log.info("session_paused id={} by={}", sessionId, operatorId);
            return SessionResponse.from(session);
        }

        if (session.getStatus() == SessionStatus.PAUSED) {
            // 恢复成 LIVE 还是 NOT_STARTED 取决于有没有开过课：
            // 从没翻过页的课堂恢复后不该突然显示「直播中」
            session.setStatus(session.getStartedAt() == null
                    ? SessionStatus.NOT_STARTED : SessionStatus.LIVE);
            session.setPausedAt(null);
            session = sessionRepository.save(session);
        }
        log.info("session_resumed id={} by={}", sessionId, operatorId);
        return SessionResponse.from(session);
    }

    // ── 屏幕共享状态（M1） ─────────────────────────────────────

    /**
     * 查询当前是否有共享流。学生进入课堂时调用。
     *
     * <p>注意这只是**服务端登记的状态**，不等于「学生此刻真的收得到画面」——
     * 媒体流是老师与学生点对点直连的，服务端不经手。
     */
    @Transactional(readOnly = true)
    public StreamStateResponse streamState(Long sessionId) {
        ClassSession session = requireSession(sessionId);
        if (!streamStateRegistry.isLive(sessionId)) {
            return StreamStateResponse.idle();
        }
        // teacherId 必须带上：学生回信令时要知道发给谁（见 StreamStateResponse 的说明）
        Long teacherId = session.getTeacher() == null ? null : session.getTeacher().getId();
        return StreamStateResponse.of(true, streamStateRegistry.startedAt(sessionId), teacherId);
    }

    /**
     * 开始 / 停止共享登记（教师专属，且只能操作自己的课堂）。
     *
     * <p>成功后广播 {@code stream.started} / {@code stream.stopped}，
     * 学生端据此显示或收起「老师正在共享屏幕」的提示。
     *
     * <p><b>服务端管不到画面本身</b>：真正的编码与传输在老师的浏览器里，
     * 这里只是登记 + 广播。老师如果把浏览器标签页直接关了，
     * 登记不会自动清除，得等他重新进控制台或课堂结束。
     * 这是 P2P 架构的固有边界，不是缺陷。
     */
    public StreamStateResponse setStreamState(Long sessionId, Long teacherId, boolean live) {
        ClassSession session = transactionTemplate.execute(status -> {
            ClassSession found = requireSession(sessionId);
            User teacher = found.getTeacher();
            if (teacher == null || !teacher.getId().equals(teacherId)) {
                throw new BusinessException(403, "这不是你的课堂");
            }
            if (live && found.getStatus() == SessionStatus.ENDED) {
                throw new BusinessException("课堂已结束，无法共享屏幕");
            }
            return found;
        });

        if (live) {
            LocalDateTime startedAt = streamStateRegistry.start(sessionId);
            classBroadcaster.streamStarted(sessionId, teacherId);
            log.info("stream_started sessionId={} teacherId={}", sessionId, teacherId);
            return StreamStateResponse.of(true, startedAt, teacherId);
        }

        boolean wasLive = streamStateRegistry.stop(sessionId);
        if (wasLive) {
            classBroadcaster.streamStopped(sessionId);
        }
        log.info("stream_stopped sessionId={} teacherId={} wasLive={}", sessionId, teacherId, wasLive);
        return StreamStateResponse.idle();
    }

    private SessionResponse endInTransaction(Long sessionId, Long teacherId) {
        ClassSession session = requireSession(sessionId);

        User teacher = session.getTeacher();
        if (teacher == null || !teacher.getId().equals(teacherId)) {
            // 与翻页同一条规矩：教师能开课，但不能结掉别人的课堂
            throw new BusinessException(403, "这不是你的课堂");
        }

        if (session.getStatus() != SessionStatus.ENDED) {
            session.setStatus(SessionStatus.ENDED);
            session.setEndedAt(nowToSecond());
            session = sessionRepository.save(session);
            log.info("session_ended id={} teacherId={}", sessionId, teacherId);
        }

        return SessionResponse.from(session);
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
            session.setStartedAt(nowToSecond());
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

    /**
     * 当前时间，**截到整秒**。
     *
     * <p>为什么不能直接用 {@code LocalDateTime.now()}：{@code class_session.started_at} /
     * {@code ended_at} 是 {@code DATETIME}（0 位小数精度），而 MySQL 写入小数秒时是
     * <b>四舍五入</b>而不是截断——`.912` 会被存成下一秒。
     * 于是「刚下课」的响应返回 23:51:57、刷新后变成 23:51:58，
     * 同一个字段两次读出来不一样。先截断再存，接口前后就一致了。
     */
    private LocalDateTime nowToSecond() {
        return LocalDateTime.now().withNano(0);
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
