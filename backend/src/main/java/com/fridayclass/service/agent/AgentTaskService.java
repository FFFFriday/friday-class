package com.fridayclass.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.CoursewareResponse;
import com.fridayclass.dto.agent.AgentFileResponse;
import com.fridayclass.dto.agent.AgentTaskRequest;
import com.fridayclass.dto.agent.AgentTaskResponse;
import com.fridayclass.entity.AiAgentRun;
import com.fridayclass.enums.AgentRunStatus;
import com.fridayclass.enums.FileFormat;
import com.fridayclass.repository.AiAgentRunRepository;
import com.fridayclass.repository.ClassSessionRepository;
import com.fridayclass.repository.CoursewareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * AI 智能体任务的发起与查询。
 *
 * <h2>为什么走「异步 + 轮询」而不是一次 POST 拿结果</h2>
 *
 * 前端 axios 的 {@code timeout} 是 <b>10 秒</b>（{@code frontend/src/api/http.js}），
 * 而一次 agent 任务要 10~60 秒（多次模型调用叠加）。
 * 同步方案<b>必然超时</b>，而且超时后前端拿不到任何进度。
 *
 * <p>这个项目里已经有现成范式：课件解析就是「发起 → 轮询进度」。
 * 沿用同一套形状，前端不必再学一种新交互。
 *
 * <h2>归属校验发生在两处</h2>
 *
 * <ol>
 *   <li><b>发起时</b>：{@code coursewareId} / {@code sessionId} 必须真的属于调用者
 *       （见 {@link #requireOwnCourseware}）；</li>
 *   <li><b>查询时</b>：轮询与下载都带 {@code ownerId} 条件
 *       （{@code findByIdAndOwnerId}）—— 否则把 url 里的 id 改一个，
 *       就能读到别人任务的完整指令与失败原因。</li>
 * </ol>
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    /** {@code ai_agent_run.error} 的列宽。 */
    private static final int MAX_ERROR_CHARS = 1000;

    /** 同一个人最多同时有几个任务在跑（含排队）。见 {@link #start} 里的说明。 */
    private static final int MAX_ACTIVE_RUNS = 3;

    private final AiAgentRunRepository runRepository;
    private final CoursewareRepository coursewareRepository;
    private final ClassSessionRepository sessionRepository;
    private final AgentLoop agentLoop;
    private final WorkspaceFileService fileService;
    private final ObjectMapper objectMapper;
    private final Executor agentExecutor;

    /**
     * 任务 ID → 当前步骤文字。
     *
     * <p><b>放内存而不是放库</b>：它每个任务要更新 3~7 次，
     * 为了一句「正在查询课堂记录…」写 7 次 UPDATE 不划算；
     * 而且它的价值只在「任务跑着的这十几秒内」。
     * 丢了也没关系 —— 轮询会照常返回状态与轮数，只是没有那句进度提示。
     */
    private final Map<Long, String> stepTexts = new ConcurrentHashMap<>();

    public AgentTaskService(AiAgentRunRepository runRepository,
                            CoursewareRepository coursewareRepository,
                            ClassSessionRepository sessionRepository,
                            AgentLoop agentLoop,
                            WorkspaceFileService fileService,
                            ObjectMapper objectMapper,
                            @Qualifier("agentExecutor") Executor agentExecutor) {
        this.runRepository = runRepository;
        this.coursewareRepository = coursewareRepository;
        this.sessionRepository = sessionRepository;
        this.agentLoop = agentLoop;
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
    }

    /**
     * 发起一次任务，<b>立刻返回 taskId</b>，真正的活在后台线程里跑。
     *
     * <p>⚠ <b>本方法刻意不加 {@code @Transactional}</b>：
     * 任务行必须在提交给线程池<b>之前</b>就落库并提交。
     * 若整个方法包在一个事务里，后台线程会在事务提交之前就去读那一行，
     * 读到的是「不存在」——表现为「任务一直停在 RUNNING、永远不动」。
     * {@code JpaRepository.save} 自带事务，所以这里保存完即已提交。
     */
    public AgentTaskResponse start(Long userId, AgentTaskRequest request) {
        // 并发闸门：一次任务要连续调 3~7 次**付费**模型，是整个项目最贵的操作。
        // 没有这个上限的话，一个连点（或者脚本循环）就能把钱烧掉，
        // 而且 2 个工人的线程池会被占满、别人正常的任务一直排队。
        // 数的是库里的 RUNNING 行，排队中的也在内，所以刚好覆盖「已受理未结束」。
        long active = runRepository.countByOwnerIdAndStatus(userId, AgentRunStatus.RUNNING);
        if (active >= MAX_ACTIVE_RUNS) {
            log.warn("agent_too_many_runs userId={} active={}", userId, active);
            throw new BusinessException(429,
                    "你已经有 " + active + " 个任务在跑，等它们结束再发起吧。");
        }

        FileFormat format = FileFormat.fromValue(request.format());
        // 文件夹名与文件名走同一套沙箱校验；这里先过一遍，出错就在发起时告诉老师，
        // 不必等模型跑完（那已经花了钱）才发现落不了盘
        String folder = WorkspacePathSandbox.requireSafeFolder(request.folder());
        Long coursewareId = requireOwnCourseware(userId, request.coursewareId());
        Long sessionId = requireOwnSession(userId, request.sessionId());
        String instruction = request.instruction().strip();

        AiAgentRun run = new AiAgentRun();
        run.setOwnerId(userId);
        run.setInstruction(instruction);
        run.setContextJson(buildContextJson(folder, format, coursewareId, sessionId));
        run.setStatus(AgentRunStatus.RUNNING);
        run.setSteps(0);
        run = runRepository.save(run);

        Long runId = run.getId();
        AgentToolContext context = new AgentToolContext(userId, folder, format, sessionId,
                coursewareId, instruction, step -> stepTexts.put(runId, step));

        try {
            agentExecutor.execute(() -> execute(runId, context));
        } catch (Exception ex) {
            // 队列满 / 池已关闭。必须把状态改掉，否则前端会永远轮询一个不会动的任务。
            log.error("agent_submit_failed runId={} userId={}", runId, userId, ex);
            markFailed(runId, "任务提交失败，请稍后重试");
            throw new BusinessException(500, "任务提交失败，请稍后重试");
        }

        log.info("agent_task_started runId={} userId={} coursewareId={} sessionId={} folder={} format={}",
                runId, userId, coursewareId, sessionId, folder, format);
        return AgentTaskResponse.running(run, stepTexts.get(runId), 0L);
    }

    /** 轮询任务状态。只返回<b>自己的</b>任务。 */
    public AgentTaskResponse status(Long userId, Long taskId) {
        AiAgentRun run = requireOwnRun(userId, taskId);
        if (run.getStatus() == AgentRunStatus.RUNNING) {
            long elapsed = run.getCreatedAt() == null ? 0L
                    : java.time.Duration.between(run.getCreatedAt(), java.time.LocalDateTime.now()).toMillis();
            return AgentTaskResponse.running(run, stepTexts.get(taskId), Math.max(elapsed, 0L));
        }
        return AgentTaskResponse.finished(run);
    }

    /** 我上传的课件（智能体页面下拉框用）。 */
    public List<CoursewareResponse> myCoursewares(Long userId) {
        return coursewareRepository.findMine(userId).stream()
                .map(CoursewareResponse::from)
                .toList();
    }

    /** 我的文件列表。 */
    public List<AgentFileResponse> listFiles(Long userId, Long sessionId) {
        return fileService.listFiles(userId, sessionId).stream()
                .map(AgentFileResponse::from)
                .toList();
    }

    /** 下载：按 id 取<b>我的</b>文件。 */
    public WorkspaceFileService.Download download(Long userId, Long fileId) {
        return fileService.resolveForDownload(userId, fileId);
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 后台线程真正跑的地方。不许抛异常出去 —— 那会让线程池静默吞掉一次任务。 */
    private void execute(Long runId, AgentToolContext context) {
        try {
            AgentLoop.Outcome outcome = agentLoop.run(context, context.prompt());
            persistOutcome(runId, outcome);
        } catch (Exception ex) {
            log.error("agent_task_crashed runId={}", runId, ex);
            markFailed(runId, "任务执行出错：" + ex.getClass().getSimpleName());
        } finally {
            // 任务已到终态，进度文字没有再用处；不清掉的话这张 map 会随任务数一直长
            stepTexts.remove(runId);
        }
    }

    private void persistOutcome(Long runId, AgentLoop.Outcome outcome) {
        runRepository.findById(runId).ifPresentOrElse(run -> {
            run.setStatus(outcome.status());
            run.setSteps(outcome.rounds());
            run.setResult(outcome.text());
            run.setError(outcome.error() == null ? null : truncate(outcome.error(), MAX_ERROR_CHARS));
            run.setPromptTokens(outcome.promptTokens());
            run.setCompletionTokens(outcome.completionTokens());
            run.setElapsedMs(outcome.elapsedMs());
            runRepository.save(run);
            log.info("agent_task_finished runId={} status={} rounds={} promptTokens={} "
                            + "completionTokens={} elapsedMs={} tools={}",
                    runId, outcome.status(), outcome.rounds(), outcome.promptTokens(),
                    outcome.completionTokens(), outcome.elapsedMs(), outcome.usedTools());
        }, () -> log.error("agent_run_missing runId={}", runId));
    }

    private void markFailed(Long runId, String message) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(AgentRunStatus.FAILED);
            run.setError(truncate(message, MAX_ERROR_CHARS));
            runRepository.save(run);
        });
    }

    private AiAgentRun requireOwnRun(Long userId, Long taskId) {
        return runRepository.findByIdAndOwnerId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));
    }

    /**
     * 校验课件归属。
     *
     * <p><b>「不存在」与「不是你的」返回同一句话</b>：区分开的话，
     * 攻击者可以靠错误信息的差异枚举出「这个 id 存在、但不是我的」，
     * 从而知道系统里有多少份课件。这句话对两种情况都成立，也不泄漏任何信息。
     */
    private Long requireOwnCourseware(Long userId, Long coursewareId) {
        if (coursewareId == null) {
            return null;
        }
        if (coursewareRepository.countOwnedBy(coursewareId, userId) == 0) {
            log.warn("agent_courseware_forbidden userId={} coursewareId={}", userId, coursewareId);
            throw new BusinessException(403, "只能使用自己上传的课件");
        }
        return coursewareId;
    }

    /** 校验课堂归属，口径同 {@link #requireOwnCourseware}。 */
    private Long requireOwnSession(Long userId, Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        if (sessionRepository.countTaughtBy(sessionId, userId) == 0) {
            log.warn("agent_session_forbidden userId={} sessionId={}", userId, sessionId);
            throw new BusinessException(403, "只能使用自己上过的课堂");
        }
        return sessionId;
    }

    /**
     * 把这次任务的选择记成一行文本，便于事后回溯「这个文件是在什么上下文里生成的」。
     *
     * <p>写成 JSON（列名就叫 context_json）而不是拼一串中文：
     * 结构化的话，将来要做「按课堂统计 AI 使用量」可以直接解析。
     */
    private String buildContextJson(String folder, FileFormat format,
                                    Long coursewareId, Long sessionId) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("folder", folder);
        context.put("format", format.name());
        context.put("coursewareId", coursewareId);
        context.put("sessionId", sessionId);
        try {
            String json = objectMapper.writeValueAsString(context);
            return json.length() <= 500 ? json : json.substring(0, 500);
        } catch (Exception ex) {
            // 只是一行追溯信息，序列化失败不该挡住任务本身
            log.warn("agent_context_json_failed reason={}", ex.getMessage());
            return null;
        }
    }

    /** 按码点截断，避免把 emoji 切成半个字符（与 AdminAuditService 口径一致）。 */
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
