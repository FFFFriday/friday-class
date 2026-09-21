package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.ClassGroupMemberResponse;
import com.fridayclass.dto.ClassGroupMembersRequest;
import com.fridayclass.dto.ClassGroupRequest;
import com.fridayclass.dto.ClassGroupResponse;
import com.fridayclass.dto.ListResult;
import com.fridayclass.dto.PageResult;
import com.fridayclass.dto.SessionResponse;
import com.fridayclass.dto.StudentCandidateResponse;
import com.fridayclass.entity.ClassGroup;
import com.fridayclass.entity.ClassGroupMember;
import com.fridayclass.entity.SessionClassGroup;
import com.fridayclass.entity.User;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.ClassGroupMemberRepository;
import com.fridayclass.repository.ClassGroupRepository;
import com.fridayclass.repository.SessionClassGroupRepository;
import com.fridayclass.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 班级管理。教师端与管理端**共用这一套**，差别只在 {@code isAdmin} 这一个开关。
 *
 * <h3>为什么用「同一个 Service + isAdmin 参数」而不是写两个类</h3>
 *
 * 管理端要做的无非是「不受归属限制地做同样的事」。写两份的话，
 * 改一处逻辑就得记得同步另一处，而漏掉的那次往往正是安全相关的那次。
 * 与既有 {@code AdminSessionService} / {@code AdminUserService} 包一层 Service 的做法一致。
 *
 * <h3>唯一的分叉点：归属校验</h3>
 *
 * {@link #requireOwnedGroup} 是教师视角的守门人 —— 教师只能碰自己建的班，
 * 碰别人的一律 403。管理员跳过这一步。
 * <b>除了这里，其余代码一行都不分叉。</b>
 *
 * <h3>为什么不记「教师管自己班」的审计</h3>
 *
 * 只有管理端跨教师的写操作记审计（见 {@link AdminAuditService} 的动作码注释）。
 * 教师日复一日地改自己班的名字如果都留痕，审计日志会被淹没，
 * 「管理员动了别人的班」这种真正该被看见的事反而沉下去。
 */
@Service
public class ClassGroupService {

    private static final Logger log = LoggerFactory.getLogger(ClassGroupService.class);

    /** 单页上限。班级列表是给人看的，不是导数据的。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 候选学生搜索单页上限。 */
    private static final int MAX_CANDIDATE_SIZE = 50;

    private final ClassGroupRepository groupRepository;
    private final ClassGroupMemberRepository memberRepository;
    private final SessionClassGroupRepository sessionGroupRepository;
    private final UserRepository userRepository;

    public ClassGroupService(ClassGroupRepository groupRepository,
                             ClassGroupMemberRepository memberRepository,
                             SessionClassGroupRepository sessionGroupRepository,
                             UserRepository userRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.sessionGroupRepository = sessionGroupRepository;
        this.userRepository = userRepository;
    }

    // ── 读 ────────────────────────────────────────────────────

    /**
     * 班级列表。
     *
     * @param actorId 当前登录用户
     * @param isAdmin true = 管理端（不看归属、可跨教师）；false = 教师端（只看自己的）
     */
    @Transactional(readOnly = true)
    public PageResult<ClassGroupResponse> list(Long actorId, boolean isAdmin, Long teacherId,
                                               String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        Page<ClassGroup> groups = isAdmin
                // 管理端：teacherId 是**筛选条件**，可空
                ? groupRepository.searchAll(teacherId != null, teacherId, kw != null, kw == null ? "" : kw, pageable)
                // 教师端：teacherId 就是他自己，是**强制条件**，不接受前端传入
                : groupRepository.searchMine(actorId, kw != null, kw == null ? "" : kw, pageable);

        return PageResult.from(groups, this::toSummary);
    }

    /** 班级详情 + 成员名单。 */
    @Transactional(readOnly = true)
    public ClassGroupResponse detail(Long groupId, Long actorId, boolean isAdmin) {
        ClassGroup group = requireOwnedGroup(groupId, actorId, isAdmin);
        List<ClassGroupMemberResponse> members = memberRepository.findByGroupWithUser(groupId)
                .stream()
                .map(ClassGroupMemberResponse::from)
                .toList();
        return ClassGroupResponse.detail(group, sessionGroupRepository.countByClassGroupId(groupId), members);
    }

    /** 我的全部班级（不带成员，供开课弹窗的多选列表）。教师端用。 */
    @Transactional(readOnly = true)
    public List<ClassGroupResponse> listMine(Long teacherId) {
        return groupRepository.findMine(teacherId).stream().map(this::toSummary).toList();
    }

    /**
     * 可加入某班的学生（搜索框）。
     *
     * <p>已在班内的人由 SQL 的 {@code not exists} 排除，见
     * {@code UserRepository#findStudentCandidates}。
     */
    @Transactional(readOnly = true)
    public ListResult<StudentCandidateResponse> candidates(Long groupId, Long actorId, boolean isAdmin,
                                                           String keyword, int page, int size) {
        requireOwnedGroup(groupId, actorId, isAdmin);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1,
                Math.min(Math.max(size, 1), MAX_CANDIDATE_SIZE));

        Page<User> found = userRepository.findStudentCandidates(groupId, kw != null, kw == null ? "" : kw, pageable);
        return ListResult.of(found.getContent().stream().map(StudentCandidateResponse::from).toList());
    }

    /**
     * 我（教师）名下班里的全部学生，供开课弹窗的「单独勾选同学」搜索。
     *
     * <p><b>只搜自己班里的学生</b>，不是全校名册 —— 理由与取舍写在
     * {@code ClassGroupMemberRepository.findMyStudents} 上。
     */
    @Transactional(readOnly = true)
    public List<StudentCandidateResponse> myStudents(Long teacherId, String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1,
                Math.min(Math.max(size, 1), MAX_CANDIDATE_SIZE));
        return memberRepository.findMyStudents(teacherId, kw != null, kw == null ? "" : kw, pageable)
                .stream()
                .map(StudentCandidateResponse::from)
                .toList();
    }

    /** 学生端：我所在的班级。 */
    @Transactional(readOnly = true)
    public List<ClassGroupResponse> myGroups(Long studentId) {
        return memberRepository.findMyGroups(studentId).stream()
                .map(ClassGroupMember::getClassGroup)
                .map(this::toSummary)
                .toList();
    }

    /**
     * 这个班开过哪些课（教师端班级详情的下半部分）。
     *
     * <p>查的是 {@code session_class_group} 关联表 —— 注意它与「谁能看这节课」无关：
     * 一个学生后来被移出班级，他照样能回顾以前上过的课，因为可见性在开课时
     * 就快照进了 {@code session_audience}。这里只是给老师看「我给这个班上了什么」。
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> sessionsOf(Long groupId, Long actorId, boolean isAdmin) {
        requireOwnedGroup(groupId, actorId, isAdmin);
        return sessionGroupRepository.findByGroupWithSession(groupId).stream()
                .map(SessionClassGroup::getSession)
                .map(SessionResponse::from)
                .toList();
    }

    // ── 写 ────────────────────────────────────────────────────

    @Transactional
    public ClassGroupResponse create(Long teacherId, ClassGroupRequest request) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new BusinessException(401, "登录已失效，请重新登录"));

        ClassGroup group = new ClassGroup();
        group.setName(request.name().trim());
        group.setDescription(blankToNull(request.description()));
        group.setTeacher(teacher);
        group = groupRepository.save(group);

        log.info("class_group_created id={} teacherId={} name={}", group.getId(), teacherId, group.getName());
        return ClassGroupResponse.summary(group, 0, 0);
    }

    @Transactional
    public ClassGroupResponse update(Long groupId, ClassGroupRequest request, Long actorId, boolean isAdmin) {
        ClassGroup group = requireOwnedGroup(groupId, actorId, isAdmin);
        group.setName(request.name().trim());
        group.setDescription(blankToNull(request.description()));
        groupRepository.save(group);
        log.info("class_group_updated id={} actorId={} admin={}", groupId, actorId, isAdmin);
        return ClassGroupResponse.summary(group,
                memberRepository.countByClassGroupId(groupId),
                sessionGroupRepository.countByClassGroupId(groupId));
    }

    /**
     * 删除班级（软删除）。
     *
     * <p>只置 {@code deleted = 1}，**不删成员行、不碰已开的课**：
     * 历史课堂的可见性早已在开课时快照进 {@code session_audience}，
     * 与班级是否存在无关。所以删掉一个班，学生们照样能回顾以前上过的课 ——
     * 这正是当初用「快照」而不是「实时查班级」的回报。
     */
    @Transactional
    public void delete(Long groupId, Long actorId, boolean isAdmin) {
        ClassGroup group = requireOwnedGroup(groupId, actorId, isAdmin);
        group.setDeleted(true);
        groupRepository.save(group);
        log.info("class_group_deleted id={} actorId={} admin={} name={}",
                groupId, actorId, isAdmin, group.getName());
    }

    /**
     * 批量加人。
     *
     * <p>三道校验，缺一不可：
     * <ol>
     *   <li>班级归属（教师只能加自己班的人）；</li>
     *   <li>这些人**必须是学生** —— 不然教师能把自己的账号加进去，
     *       或者更糟：把某个管理员加进班，让「班级名单」变成提权跳板；</li>
     *   <li>已在班内的跳过，不报错（重复点提交是常见操作）。</li>
     * </ol>
     *
     * @return 实际新增的人数（去重并排除已在班内之后）
     */
    @Transactional
    public int addMembers(Long groupId, ClassGroupMembersRequest request, Long actorId, boolean isAdmin) {
        ClassGroup group = requireOwnedGroup(groupId, actorId, isAdmin);

        Set<Long> wanted = new HashSet<>(request.userIds());
        wanted.remove(null);

        List<User> students = userRepository.findAllById(wanted).stream()
                .filter(u -> Boolean.FALSE.equals(u.getDeleted()))
                .filter(u -> !Boolean.TRUE.equals(u.getDisabled()))
                .filter(u -> u.getRole() == Role.STUDENT)
                .toList();

        if (students.isEmpty()) {
            throw new BusinessException("选中的账号里没有可用（未删除、未禁用）的学生");
        }

        List<ClassGroupMember> toAdd = new ArrayList<>();
        for (User student : students) {
            // 已在班内就跳过，而不是撞唯一键报 500
            if (memberRepository.findByClassGroupIdAndUserId(groupId, student.getId()).isPresent()) {
                continue;
            }
            ClassGroupMember member = new ClassGroupMember();
            member.setClassGroup(group);
            member.setUser(student);
            toAdd.add(member);
        }

        if (!toAdd.isEmpty()) {
            memberRepository.saveAll(toAdd);
        }
        log.info("class_group_members_added groupId={} requested={} added={} actorId={}",
                groupId, request.userIds().size(), toAdd.size(), actorId);
        return toAdd.size();
    }

    /** 把某个学生移出班级。不在班里也按成功处理（幂等）。 */
    @Transactional
    public void removeMember(Long groupId, Long userId, Long actorId, boolean isAdmin) {
        requireOwnedGroup(groupId, actorId, isAdmin);
        memberRepository.findByClassGroupIdAndUserId(groupId, userId)
                .ifPresent(memberRepository::delete);
        log.info("class_group_member_removed groupId={} userId={} actorId={}", groupId, userId, actorId);
    }

    // ── 供开课使用 ─────────────────────────────────────────────

    /**
     * 把选中的班级摊平成学生 ID 集合（合班 = 多个班取并集）。
     *
     * <p><b>必须校验这些班都是这位教师自己的</b>：否则教师 A 传一个教师 B 的班级 ID，
     * 就能把 B 班的学生全部拉进自己的课堂。这是横向越权，不是显示问题。
     *
     * @param groupIds 选中的班级；空集合表示没选班级，返回空集合（不算错误）
     */
    @Transactional(readOnly = true)
    public Set<Long> resolveAudienceUserIds(Collection<Long> groupIds, Long teacherId) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> unique = new HashSet<>(groupIds);
        unique.remove(null);

        for (Long groupId : unique) {
            ClassGroup group = groupRepository.findById(groupId)
                    .filter(g -> !Boolean.TRUE.equals(g.getDeleted()))
                    .orElseThrow(() -> new BusinessException(404, "班级不存在：" + groupId));
            if (!group.getTeacher().getId().equals(teacherId)) {
                throw new BusinessException(403, "不能把别的教师的班级拉进自己的课堂");
            }
        }

        // 合班时两个班可能有共同的学生 —— 交给 Set 去重，
        // 与 session_audience 的唯一键是双保险
        return new HashSet<>(memberRepository.findUserIdsByClassGroupIds(unique));
    }

    /** 取一批班级的实体（开课时写 session_class_group 用）。调用方需自行保证归属已校验。 */
    @Transactional(readOnly = true)
    public List<ClassGroup> findAllByIds(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return groupRepository.findAllById(groupIds);
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * 取班级并校验归属。<b>本类唯一的分叉点。</b>
     *
     * <p>「不存在」给 404、「存在但不是你的」给 403 —— 分开是刻意的：
     * 都返回 404 更隐蔽，但会让老师以为自己手滑删了班，跑来问「我的班呢」。
     * 这里的信息泄露风险可以接受（对方本来就知道有这么一个班才会去碰它）。
     */
    private ClassGroup requireOwnedGroup(Long groupId, Long actorId, boolean isAdmin) {
        ClassGroup group = groupRepository.findById(groupId)
                .filter(g -> !Boolean.TRUE.equals(g.getDeleted()))
                .orElseThrow(() -> new BusinessException(404, "班级不存在"));

        if (isAdmin) {
            return group;
        }
        if (group.getTeacher() == null || !group.getTeacher().getId().equals(actorId)) {
            log.warn("class_group_forbidden groupId={} actorId={}", groupId, actorId);
            throw new BusinessException(403, "只能操作自己创建的班级");
        }
        return group;
    }

    private ClassGroupResponse toSummary(ClassGroup group) {
        return ClassGroupResponse.summary(group,
                memberRepository.countByClassGroupId(group.getId()),
                sessionGroupRepository.countByClassGroupId(group.getId()));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
