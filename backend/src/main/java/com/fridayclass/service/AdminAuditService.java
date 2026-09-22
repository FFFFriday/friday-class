package com.fridayclass.service;

import com.fridayclass.entity.AdminAuditLog;
import com.fridayclass.repository.AdminAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理操作审计日志。<b>所有管理端写操作都必须经这里留痕。</b>
 *
 * <h3>为什么收口到一个类</h3>
 * 审计最容易出的问题不是「写错」，是「漏写」——新加一个管理接口时忘了记一笔，
 * 而且没有任何报错提醒你。收口成 {@link #record} 一处之后，
 * 评审时只要搜「哪些管理接口没调它」就够了。
 *
 * <h3>为什么不出现在前端功能里</h3>
 * 按 Friday 指示：**只做「写 + 查」，不配图表、不做统计**。
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    /** 日志列表单页上限。审计日志是流水，别让人一次拉一百万条把内存打满。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final AdminAuditLogRepository auditRepository;

    public AdminAuditService(AdminAuditLogRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    // ── 动作码。取值约定见 02_技术约定 §三 ──────────────────────

    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_DISABLE = "USER_DISABLE";
    public static final String USER_ENABLE = "USER_ENABLE";
    public static final String USER_RESET_PWD = "USER_RESET_PWD";
    public static final String USER_CHANGE_ROLE = "USER_CHANGE_ROLE";
    public static final String USER_DELETE = "USER_DELETE";

    public static final String SESSION_FORCE_END = "SESSION_FORCE_END";
    public static final String SESSION_PAUSE = "SESSION_PAUSE";
    public static final String SESSION_RESUME = "SESSION_RESUME";
    public static final String SESSION_PAUSE_ALL = "SESSION_PAUSE_ALL";
    public static final String SESSION_RESUME_ALL = "SESSION_RESUME_ALL";
    public static final String SESSION_END_ALL = "SESSION_END_ALL";
    public static final String SESSION_KICK = "SESSION_KICK";

    public static final String COURSEWARE_DELETE = "COURSEWARE_DELETE";
    public static final String COURSEWARE_REPARSE = "COURSEWARE_REPARSE";
    public static final String STORAGE_ORPHAN_CLEAN = "STORAGE_ORPHAN_CLEAN";

    // 班级体系（问题点 8）。只有**管理端跨教师**的操作才记审计 ——
    // 教师管理自己的班属于日常操作，逐条记流水会把审计日志淹掉，
    // 反而让「谁动了别人的班」这种真正该被看见的事沉下去。
    public static final String CLASS_CREATE = "CLASS_CREATE";
    public static final String CLASS_UPDATE = "CLASS_UPDATE";
    /**
     * 更换班主任（把班从一个教师转给另一个）。
     *
     * <p>单独一个动作码，不混进 {@link #CLASS_UPDATE} —— 改名和换归属不是一回事：
     * 前者只是换个称呼，后者会让一个老师**立刻失去**这个班。
     * 混在一起的话，出事时得一条条翻「修改班级」的日志才能找出那一次。
     */
    public static final String CLASS_REASSIGN = "CLASS_REASSIGN";
    public static final String CLASS_DELETE = "CLASS_DELETE";
    public static final String CLASS_MEMBER_ADD = "CLASS_MEMBER_ADD";
    public static final String CLASS_MEMBER_REMOVE = "CLASS_MEMBER_REMOVE";

    // AI 智能体（问题点 2 的第 5 条）。
    //
    // 这里**与班级体系的口径不同**，是刻意的：班级那边只有跨教师的操作才记审计，
    // 而 AI 写文件是**本项目第一个由模型产生副作用的动作**——
    // 「谁让 AI 写了什么、写到了哪」本身就值得留痕，与是不是管理员无关。
    // 而且这类操作量很小（一位老师一天生成几个文件），不会把审计日志淹掉。
    //
    // 注：任务的 token 用量与轮数记在 ai_agent_run 表，不重复记到这里。
    public static final String AI_FILE_WRITE = "AI_FILE_WRITE";

    // ── 对象类型 ──────────────────────────────────────────────

    public static final String TARGET_USER = "USER";
    public static final String TARGET_SESSION = "SESSION";
    public static final String TARGET_COURSEWARE = "COURSEWARE";
    public static final String TARGET_STORAGE = "STORAGE";
    public static final String TARGET_CLASS_GROUP = "CLASS_GROUP";
    public static final String TARGET_AI_FILE = "AI_FILE";

    /**
     * 记一笔。
     *
     * <p><b>{@code REQUIRES_NEW}</b>：审计日志必须独立于业务事务。
     * 用默认传播的话，业务回滚会把日志一起回滚掉——
     * 而「管理操作失败了」恰恰是更该留下记录的事（谁在什么时候试图删了什么）。
     *
     * <p><b>失败只记日志、不抛异常</b>：审计写不进去不该让管理操作失败。
     * 这个取舍是有意的——审计是事后追责用的，不是业务正确性的前提。
     * （反过来说，如果哪天真需要「审计失败就不许操作」，那要改的是这里，不是调用方。）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long adminId, String action, String targetType, Long targetId, String detail) {
        try {
            AdminAuditLog entry = new AdminAuditLog();
            entry.setAdminId(adminId);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(truncate(detail, 500));
            auditRepository.save(entry);
            log.info("admin_audit adminId={} action={} targetType={} targetId={}",
                    adminId, action, targetType, targetId);
        } catch (Exception ex) {
            log.error("admin_audit_failed adminId={} action={} targetId={}",
                    adminId, action, targetId, ex);
        }
    }

    /**
     * 查日志。两个筛选条件都可空。
     *
     * <p>返回 {@link Page} 而不是 DTO：分页信封由 Controller 用 {@code PageResult.from}
     * 统一转（那里负责「Spring Data 从 0 页开始、契约从 1 页开始」的换算）。
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLog> list(Long adminId, String action, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest request = PageRequest.of(Math.max(page, 1) - 1, safeSize);

        if (adminId != null && action != null) {
            return auditRepository.findByAdminIdAndActionOrderByCreatedAtDescIdDesc(adminId, action, request);
        }
        if (adminId != null) {
            return auditRepository.findByAdminIdOrderByCreatedAtDescIdDesc(adminId, request);
        }
        if (action != null) {
            return auditRepository.findByActionOrderByCreatedAtDescIdDesc(action, request);
        }
        return auditRepository.findAllByOrderByCreatedAtDescIdDesc(request);
    }

    /** 按码点截断，避免把 emoji 切成半个字符。 */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxChars));
    }
}
