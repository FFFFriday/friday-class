package com.fridayclass.service;

import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.ChatHistoryResponse;
import com.fridayclass.dto.QaRecordResponse;
import com.fridayclass.dto.SessionRecordOverview;
import com.fridayclass.dto.StudentQaGroup;
import com.fridayclass.entity.ClassSession;
import com.fridayclass.entity.QaRecord;
import com.fridayclass.enums.Role;
import com.fridayclass.repository.ChatMessageRepository;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.QaRecordRepository;
import com.fridayclass.repository.SessionParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 课堂记录的读取侧（M5）。
 *
 * <h3>数据源都是现成的，不新建存储</h3>
 * 发言来自 {@code chat_message}（M2），AI 问答来自 {@code qa_record}（M4），
 * 出席来自 {@code session_participant}（M1）。本类只负责按课堂把它们聚起来。
 */
@Service
public class SessionRecordService {

    private final ClassSessionRepository sessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final QaRecordRepository qaRecordRepository;
    private final SessionParticipantRepository participantRepository;
    private final ChatService chatService;

    public SessionRecordService(ClassSessionRepository sessionRepository,
                                ChatMessageRepository chatMessageRepository,
                                QaRecordRepository qaRecordRepository,
                                SessionParticipantRepository participantRepository,
                                ChatService chatService) {
        this.sessionRepository = sessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.qaRecordRepository = qaRecordRepository;
        this.participantRepository = participantRepository;
        this.chatService = chatService;
    }

    /**
     * 校验「这个人能不能看这堂课的记录」，并返回课堂。
     *
     * <p>权限是<b>本课堂的授课教师，或任意管理员</b>。学生看不到——
     * 记录里包含全班同学的提问与发言，不是个人数据。
     */
    @Transactional(readOnly = true)
    public ClassSession requireAccess(Long sessionId, Long userId, String role) {
        ClassSession session = sessionRepository.findDetailById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "课堂不存在"));

        boolean isAdmin = Role.ADMIN.name().equals(role);
        boolean isTeacher = session.getTeacher() != null
                && session.getTeacher().getId().equals(userId);

        if (!isAdmin && !isTeacher) {
            throw new BusinessException(403, "只有本课堂的教师或管理员可以查看课堂记录");
        }
        return session;
    }

    /** 概览：时长、参与人数、发言数、问答数。 */
    @Transactional(readOnly = true)
    public SessionRecordOverview overview(Long sessionId, Long userId, String role) {
        ClassSession session = requireAccess(sessionId, userId, role);

        long participants = participantRepository.countBySessionId(sessionId);
        long chatCount = chatMessageRepository.countActiveBySession(sessionId);
        long qaCount = qaRecordRepository.countBySessionId(sessionId);

        // 「聊过的学生数」：按学生去重。它在教学上的意义和「问答条数」完全不同——
        // 一个学生问 20 次是 qaCount=20、studentQaCount=1。
        long studentQaCount = qaRecordRepository.findBySessionWithStudentAndPage(sessionId).stream()
                .map(r -> r.getStudent() == null ? null : r.getStudent().getId())
                .distinct()
                .count();

        return new SessionRecordOverview(
                session.getId(),
                session.getTitle(),
                session.getCourseware() == null ? null : session.getCourseware().getName(),
                session.getStatus() == null ? null : session.getStatus().name(),
                session.getStartedAt(),
                session.getEndedAt(),
                durationMinutes(session),
                participants,
                chatCount,
                qaCount,
                studentQaCount);
    }

    /**
     * 发言时间线。复用讨论区的分页语义（游标而非 offset）。
     *
     * <p>不直接暴露给 ChatController 是因为那条路径只要求登录，
     * 而记录页要求教师/管理员——权限口径不一样，不能共用一个入口。
     */
    @Transactional(readOnly = true)
    public ChatHistoryResponse chatTimeline(Long sessionId, Long userId, String role,
                                            Long beforeId, Long afterId, Integer limit) {
        requireAccess(sessionId, userId, role);
        return chatService.history(sessionId, afterId, beforeId, limit);
    }

    /**
     * 按学生分组的 AI 问答。<b>没聊过的学生不会出现。</b>
     *
     * <p>分组是<b>直接从问答记录出发</b>做的，而不是「先取全班学生、再逐个查记录」。
     * 后者需要一步特判才能把没聊过的人滤掉，而且必然产生 N+1 查询。
     * 从记录出发则天然满足需求：没有记录，就没有这一组。
     */
    @Transactional(readOnly = true)
    public List<StudentQaGroup> studentQa(Long sessionId, Long userId, String role) {
        requireAccess(sessionId, userId, role);

        // 查询已按「学生 ID 升序、记录 ID 升序」排好，所以顺序扫描即可分组
        List<QaRecord> records = qaRecordRepository.findBySessionWithStudentAndPage(sessionId);

        List<StudentQaGroup> groups = new ArrayList<>();
        Long currentStudentId = null;
        String currentNickname = null;
        List<QaRecordResponse> buffer = new ArrayList<>();

        for (QaRecord record : records) {
            var student = record.getStudent();
            Long studentId = student == null ? null : student.getId();

            if (currentStudentId == null || !currentStudentId.equals(studentId)) {
                if (currentStudentId != null) {
                    groups.add(StudentQaGroup.of(currentStudentId, currentNickname, buffer));
                }
                currentStudentId = studentId;
                currentNickname = student == null ? null : student.getNickname();
                buffer = new ArrayList<>();
            }
            buffer.add(QaRecordResponse.from(record));
        }
        if (currentStudentId != null) {
            groups.add(StudentQaGroup.of(currentStudentId, currentNickname, buffer));
        }

        return groups;
    }

    /**
     * 这节课用来喂给总结模型的两段文本。也供 {@code SessionSummaryService} 复用。
     *
     * <p>只取<b>有效</b>的发言与问答：已撤回的发言不进总结——
     * 老师撤回它就是因为不该被看见，总结再把它捡回来等于撤回失效。
     */
    @Transactional(readOnly = true)
    public SessionTexts collectTexts(Long sessionId) {
        StringBuilder chat = new StringBuilder();
        chatMessageRepository.findWithUserBySession(sessionId, org.springframework.data.domain.PageRequest.of(0, 500))
                .stream()
                .filter(m -> m.getStatus() == com.fridayclass.enums.ChatMessageStatus.NORMAL)
                .forEach(m -> chat.append(m.getUser() == null ? "同学"
                                : (m.getUser().getNickname() == null ? "同学" : m.getUser().getNickname()))
                        .append("：").append(m.getContent()).append('\n'));

        StringBuilder qa = new StringBuilder();
        for (QaRecord record : qaRecordRepository.findBySessionWithStudentAndPage(sessionId)) {
            String name = record.getStudent() == null || record.getStudent().getNickname() == null
                    ? "同学" : record.getStudent().getNickname();
            qa.append(name).append(" 问：").append(record.getQuestion()).append('\n');
            qa.append("助教 答：").append(record.getAnswer() == null ? "" : record.getAnswer()).append('\n');
        }

        return new SessionTexts(chat.toString(), qa.toString());
    }

    /** 上课时长（分钟）。没开始的返回 0；还在上的算到此刻。 */
    private long durationMinutes(ClassSession session) {
        LocalDateTime start = session.getStartedAt();
        if (start == null) {
            return 0L;
        }
        LocalDateTime end = session.getEndedAt() == null ? LocalDateTime.now() : session.getEndedAt();
        long minutes = Duration.between(start, end).toMinutes();
        return Math.max(0L, minutes);
    }

    /** 讨论区与问答的原文。 */
    public record SessionTexts(String chatText, String qaText) {
    }
}
