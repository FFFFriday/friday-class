package com.fridayclass.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fridayclass.common.BusinessException;
import com.fridayclass.dto.ParseProgressResponse;
import com.fridayclass.entity.AiParseTask;
import com.fridayclass.entity.Courseware;
import com.fridayclass.entity.CoursewarePage;
import com.fridayclass.entity.KnowledgePoint;
import com.fridayclass.entity.PresetQuestion;
import com.fridayclass.enums.AiTaskStatus;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.llm.CallKind;
import com.fridayclass.llm.DeepSeekClient;
import com.fridayclass.llm.LlmResult;
import com.fridayclass.llm.PromptTemplates;
import com.fridayclass.repository.AiParseTaskRepository;
import com.fridayclass.repository.CoursewarePageRepository;
import com.fridayclass.repository.CoursewareRepository;
import com.fridayclass.repository.KnowledgePointRepository;
import com.fridayclass.repository.PresetQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * F002 课件解析智能体：逐页调模型抽取知识点与预置提问。
 *
 * <h3>为什么是异步</h3>
 * 一份 103 页的课件要调 103 次模型。同步做的话「触发解析」这个接口要挂好几分钟，
 * 浏览器早就超时了。所以拆成「触发 → 立即返回 → 后台跑 → 前端轮询进度」。
 *
 * <h3>事务边界（本类最容易写错的地方）</h3>
 * <b>模型调用绝不能包在事务里。</b>一次调用要 2 秒、重试还能到 60 秒，
 * 事务开着就等于占着一条数据库连接干等网络——并发几个课件就把连接池耗尽。
 * 所以本类<b>没有</b>类级 {@code @Transactional}，只有写库的几个短事务用
 * {@link TransactionTemplate} 显式包起来，写法与 {@code ClassSessionService} 一致。
 *
 * <h3>并发模型</h3>
 * 所有页任务一次性提交进一个固定大小的池（见 {@code AiExecutorConfig}），
 * <b>没有任何线程在等待</b>：每个任务跑完把剩余计数减一，
 * 减到 0 的那个任务负责收尾。这样不会出现「池被占满 → 排不进队 → 永远等不到」的死锁。
 */
@Service
public class AiParseService {

    private static final Logger log = LoggerFactory.getLogger(AiParseService.class);

    /** 提示词要求知识点 2~5 个，这里放宽到 8 兜底：模型偶尔会多给，多出来的丢掉即可。 */
    private static final int MAX_ITEMS_PER_PAGE = 8;

    /** 提示词要求每条 10~40 字；这里放宽到 120，超过的多半是模型跑偏在写小作文。 */
    private static final int MAX_ITEM_CHARS = 120;

    private static final String BUSY_MESSAGE = "该课件正在解析中，请稍后刷新进度";

    /** 写库遇到死锁时的最大尝试次数（含首次）。 */
    private static final int PERSIST_MAX_ATTEMPTS = 3;

    private final CoursewareRepository coursewareRepository;
    private final CoursewarePageRepository pageRepository;
    private final KnowledgePointRepository knowledgePointRepository;
    private final PresetQuestionRepository presetQuestionRepository;
    private final AiParseTaskRepository taskRepository;
    private final DeepSeekClient llmClient;
    private final Executor parseExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 当前正在解析的课件 ID。
     *
     * <p><b>只挡得住同一个 JVM</b>。多实例部署时两个实例会各解析一遍——
     * 所以 {@link #startTask} 里还有一道<b>数据库层面</b>的检查（有没有未结束的任务行）。
     * 内存这道更快也更早（能挡住「还没建任务行」的并发窗口），两道都要有。
     */
    private final Set<Long> runningCoursewares = ConcurrentHashMap.newKeySet();

    /**
     * 页结果写库的串行锁。<b>这不是保守，是修一个实测出来的真问题。</b>
     *
     * <p>页级并发跑起来后，日志里出现过 MySQL 死锁：
     * <pre>
     * Deadlock found when trying to get lock;
     *   insert into knowledge_point (content,created_at,page_id,sort_order) values (?,?,?,?)
     * </pre>
     *
     * <p>成因是「先删后插」+ 并发：{@code DELETE FROM knowledge_point WHERE page_id = ?}
     * 走的是 {@code page_id} 索引，在 REPEATABLE READ 下取的是<b>间隙锁</b>（即使一行都没删到）。
     * 三个线程各自删除相邻 page_id 的区间后再插入，插入意向锁与对方的间隙锁互斥，形成环。
     *
     * <p>代价可以忽略：写库一次只有几毫秒，而模型调用要 1.5 秒——
     * 真正慢的部分（调模型）仍然完全并发。69 页串行写库总共也就多花几百毫秒。
     *
     * <p>只挡得住单实例；多实例部署时靠 {@link #runWithLockRetry} 的重试兜底。
     */
    private final ReentrantLock persistLock = new ReentrantLock();

    public AiParseService(CoursewareRepository coursewareRepository,
                          CoursewarePageRepository pageRepository,
                          KnowledgePointRepository knowledgePointRepository,
                          PresetQuestionRepository presetQuestionRepository,
                          AiParseTaskRepository taskRepository,
                          DeepSeekClient llmClient,
                          @Qualifier("aiParseExecutor") Executor parseExecutor,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.coursewareRepository = coursewareRepository;
        this.pageRepository = pageRepository;
        this.knowledgePointRepository = knowledgePointRepository;
        this.presetQuestionRepository = presetQuestionRepository;
        this.taskRepository = taskRepository;
        this.llmClient = llmClient;
        this.parseExecutor = parseExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 触发解析（教师手动点按钮）。立即返回当前进度，真正的解析在后台跑。
     *
     * <p><b>顺序很重要</b>：先占内存锁 → 短事务建任务行并置 PARSING →
     * <b>事务提交后</b>才投递异步任务。反过来的话，异步任务一开跑就查库，
     * 而那时事务还没提交，它查不到刚建的任务行。
     */
    public ParseProgressResponse trigger(Long coursewareId) {
        if (!runningCoursewares.add(coursewareId)) {
            throw new BusinessException(BUSY_MESSAGE);
        }

        Long taskId;
        try {
            taskId = transactionTemplate.execute(status -> startTask(coursewareId));
        } catch (RuntimeException ex) {
            // 事务失败（课件不存在 / 已有未结束任务）必须把内存锁还回去，
            // 否则这份课件在本次进程生命周期内再也触发不了解析了
            runningCoursewares.remove(coursewareId);
            throw ex;
        }

        try {
            parseExecutor.execute(() -> runParse(coursewareId, taskId));
        } catch (RuntimeException ex) {
            log.error("ai_parse_submit_failed coursewareId={} taskId={}", coursewareId, taskId, ex);
            runningCoursewares.remove(coursewareId);
            markTaskFailed(taskId, "解析任务启动失败：" + rootMessage(ex));
            throw new BusinessException(500, "解析任务启动失败，请重试");
        }

        return progress(coursewareId);
    }

    /** 查询解析进度。从未解析过也能调用，返回 {@code status = null} 的空进度。 */
    @Transactional(readOnly = true)
    public ParseProgressResponse progress(Long coursewareId) {
        Courseware courseware = coursewareRepository.findById(coursewareId)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));
        AiParseTask task = taskRepository.findTopByCoursewareIdOrderByIdDesc(coursewareId).orElse(null);
        // 用**实际页数**而不是 courseware.page_count：两者在库里确实存在不一致的数据，
        // 用后者会让进度条永远走不满（详见 CoursewarePageRepository.countByCoursewareId）
        int actualPages = (int) pageRepository.countByCoursewareId(coursewareId);
        return ParseProgressResponse.from(courseware.getId(),
                courseware.getStatus().name(), task, actualPages);
    }

    /** 建任务行 + 把课件置为 PARSING。短事务，不碰模型。 */
    private Long startTask(Long coursewareId) {
        Courseware courseware = coursewareRepository.findById(coursewareId)
                .orElseThrow(() -> new BusinessException(404, "课件不存在"));

        // 同样用实际页数：courseware.page_count 可能是过期的
        int pageCount = (int) pageRepository.countByCoursewareId(coursewareId);
        if (pageCount <= 0) {
            throw new BusinessException("该课件还没有可解析的页面，无法解析");
        }

        AiParseTask latest = taskRepository.findTopByCoursewareIdOrderByIdDesc(coursewareId).orElse(null);
        if (latest != null && isUnfinished(latest.getStatus())) {
            throw new BusinessException(BUSY_MESSAGE);
        }

        AiParseTask task = new AiParseTask();
        task.setCourseware(courseware);
        task.setStatus(AiTaskStatus.RUNNING);
        task.setCurrentPage(0);
        task.setTotalPages(pageCount);
        task.setStartedAt(nowToSecond());
        task = taskRepository.save(task);

        courseware.setStatus(CoursewareStatus.PARSING);
        coursewareRepository.save(courseware);

        log.info("ai_parse_started coursewareId={} taskId={} totalPages={}",
                coursewareId, task.getId(), pageCount);
        return task.getId();
    }

    /**
     * 后台跑一份课件的全部页。
     *
     * <p>这个方法本身<b>不抛异常给调用方</b>（它在池线程里跑）：所有失败都记日志 + 落到任务状态。
     */
    private void runParse(Long coursewareId, Long taskId) {
        try {
            List<CoursewarePage> pages = pageRepository.findByCoursewareIdOrderByPageNoAsc(coursewareId);
            int total = pages.size();

            if (total == 0) {
                finishTask(coursewareId, taskId, 0, 0);
                return;
            }

            // 计数必须在提交任务**之前**建好：任务一提交就可能开跑，
            // 先提交再初始化计数会漏掉「跑得比初始化还快」的那些任务。
            AtomicInteger remaining = new AtomicInteger(total);
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);

            int rejected = 0;
            for (CoursewarePage page : pages) {
                try {
                    parseExecutor.execute(
                            () -> parseOnePage(coursewareId, page, taskId, processed, failed, remaining));
                } catch (RuntimeException ex) {
                    // 队列是无界的，正常情况走不到这里；只有应用正在关停时才会被拒绝。
                    // 这些页永远跑不到了，必须手工把它们从计数里扣掉——
                    // 否则 remaining 永远减不到 0，收尾逻辑不执行，任务会永远停在 RUNNING。
                    rejected++;
                    log.warn("ai_parse_page_rejected coursewareId={} pageNo={}",
                            coursewareId, page.getPageNo());
                }
            }

            if (rejected > 0) {
                failed.addAndGet(rejected);
                if (remaining.addAndGet(-rejected) == 0) {
                    finishTask(coursewareId, taskId, processed.get(), failed.get());
                }
            }
        } catch (Exception ex) {
            log.error("ai_parse_aborted coursewareId={} taskId={}", coursewareId, taskId, ex);
            runningCoursewares.remove(coursewareId);
            markTaskFailed(taskId, "解析失败：" + rootMessage(ex));
        }
    }

    /**
     * 解析一页。**任何单页的失败都不影响其他页**——一页排版异常不该让另外 78 页白解析。
     *
     * <p>{@code finally} 里做两件事：推进进度、判断自己是不是最后一个。
     * 放在 finally 是刻意的：哪怕这一页抛了异常，计数也必须减，
     * 否则任务永远收不了尾。
     */
    private void parseOnePage(Long coursewareId, CoursewarePage page, Long taskId,
                              AtomicInteger processed, AtomicInteger failed, AtomicInteger remaining) {
        int pageNo = page.getPageNo();
        ParsedPage parsed = null;

        try {
            String text = page.getTextContent();
            if (text == null || text.isBlank()) {
                // 空页不调模型。省一次调用，结果完全一样（提示词第 3 条本来就要求返回空数组）。
                log.debug("ai_parse_skip_blank_page coursewareId={} pageNo={}", coursewareId, pageNo);
            } else {
                LlmResult result = llmClient.chat(CallKind.PARSE, null,
                        PromptTemplates.parsePage(pageNo, text), true);
                parsed = readModelJson(result.content(), pageNo);
            }
        } catch (Exception ex) {
            failed.incrementAndGet();
            log.warn("ai_parse_page_failed coursewareId={} pageNo={} reason={}",
                    coursewareId, pageNo, rootMessage(ex));
        } finally {
            int doneCount = processed.incrementAndGet();
            try {
                persistPage(page.getId(), parsed, taskId, doneCount);
            } catch (Exception ex) {
                // 写库失败和解析失败一样：记一笔，别让收尾计数断掉
                failed.incrementAndGet();
                log.error("ai_parse_persist_failed coursewareId={} pageNo={}", coursewareId, pageNo, ex);
            }
            if (remaining.decrementAndGet() == 0) {
                finishTask(coursewareId, taskId, processed.get(), failed.get());
            }
        }
    }

    /**
     * 一页的结果落库：**先删该页旧数据再插新的**，这样重复解析不会产生重复行
     * （设计文档 §2.5 的幂等要求）。顺带把进度推进到 {@code doneCount}。
     *
     * <p>两类写操作放同一个事务：进度和数据要么一起可见，要么一起不可见，
     * 不会出现「进度显示 50% 但那一页的知识点还没写」。
     */
    private void persistPage(Long pageId, ParsedPage parsed, Long taskId, int doneCount) {
        // 串行 + 死锁重试，理由见 persistLock 字段的注释
        persistLock.lock();
        try {
            runWithLockRetry(() -> persistPageInTx(pageId, parsed, taskId, doneCount));
        } finally {
            persistLock.unlock();
        }
    }

    private void persistPageInTx(Long pageId, ParsedPage parsed, Long taskId, int doneCount) {
        transactionTemplate.executeWithoutResult(status -> {
            // parsed == null 表示这一页解析**失败**（或为空页而跳过），旧数据要原样保留；
            // parsed != null 表示这一页解析成功，那么无论结果里有几条内容，
            // 都要先把旧数据清空——「成功但结果为空」（如封面页）必须覆盖掉上一版的陈旧知识点，
            // 否则重新解析后，封面页会一直留着上一轮解析出来的内容。
            if (parsed != null && pageRepository.existsById(pageId)) {
                // getReferenceById 拿到的是代理对象，插外键时不必真的 SELECT 一次
                CoursewarePage pageRef = pageRepository.getReferenceById(pageId);

                // 顺序不能反：在执行批量删除前 Hibernate 会先冲刷待处理的插入，
                // 所以「先删后插」是安全的；反过来会把刚插入的又删掉。
                knowledgePointRepository.deleteByPageId(pageId);
                presetQuestionRepository.deleteByPageId(pageId);

                int order = 0;
                for (String content : parsed.knowledgePoints()) {
                    KnowledgePoint point = new KnowledgePoint();
                    point.setPage(pageRef);
                    point.setContent(content);
                    point.setSortOrder(order++);
                    knowledgePointRepository.save(point);
                }

                order = 0;
                for (String content : parsed.presetQuestions()) {
                    PresetQuestion question = new PresetQuestion();
                    question.setPage(pageRef);
                    question.setContent(content);
                    question.setSortOrder(order++);
                    presetQuestionRepository.save(question);
                }
            }

            taskRepository.findById(taskId).ifPresent(task -> {
                task.setCurrentPage(doneCount);
                taskRepository.save(task);
            });
        });
    }

    /**
     * 收尾：写任务终态 + 改课件状态 + 释放内存锁。
     *
     * <p>状态映射按设计文档 §2.3 的表：
     * <ul>
     *   <li>全部页成功 → 任务 {@code SUCCESS}，课件 {@code PARSED}</li>
     *   <li>部分页失败 → 任务 {@code PARTIAL}，课件仍置 {@code PARSED}
     *       （任务级的 PARTIAL 标记就是教师端提示「部分页未解析」的依据）</li>
     *   <li>全部页失败 → 任务 {@code FAILED}，课件 {@code FAILED}（重试入口据此触发）</li>
     * </ul>
     */
    private void finishTask(Long coursewareId, Long taskId, int processed, int failed) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AiParseTask task = taskRepository.findById(taskId).orElse(null);
                // 课件可能已被删除 —— findById 受 @SQLRestriction("deleted = 0") 约束，
                // 已删课件返回空，我们正好跳过，不会往一个墓碑上写状态
                Courseware courseware = coursewareRepository.findById(coursewareId).orElse(null);

                AiTaskStatus taskStatus;
                if (failed == 0) {
                    taskStatus = AiTaskStatus.SUCCESS;
                } else if (processed > 0 && failed >= processed) {
                    taskStatus = AiTaskStatus.FAILED;
                } else {
                    taskStatus = AiTaskStatus.PARTIAL;
                }

                if (task != null) {
                    task.setStatus(taskStatus);
                    task.setFinishedAt(nowToSecond());
                    task.setErrorMessage(failed == 0
                            ? null
                            : failed + " 页解析失败（共 " + processed + " 页），其余页已完成");
                    taskRepository.save(task);
                }

                if (courseware != null) {
                    courseware.setStatus(taskStatus == AiTaskStatus.FAILED
                            ? CoursewareStatus.FAILED
                            : CoursewareStatus.PARSED);
                    coursewareRepository.save(courseware);
                }

                log.info("ai_parse_finished coursewareId={} taskId={} processed={} failed={} taskStatus={}",
                        coursewareId, taskId, processed, failed, taskStatus);
            });
        } catch (Exception ex) {
            log.error("ai_parse_finish_failed coursewareId={} taskId={}", coursewareId, taskId, ex);
        } finally {
            // 无论在哪个分支退出，锁都必须还回去
            runningCoursewares.remove(coursewareId);
        }
    }

    /**
     * 死锁重试。
     *
     * <p>MySQL 的死锁是<b>正常现象</b>而不是故障：InnoDB 检测到环就会主动牺牲一个事务，
     * 被牺牲的那个直接重试即可成功。这里最多重试 {@value #PERSIST_MAX_ATTEMPTS} 次，
     * 每次退避 50ms / 100ms（死锁是瞬时状态，等太久没意义）。
     *
     * <p>有了 {@link #persistLock} 之后，单实例下基本不会再走到这里；
     * 留着它是为了多实例部署，以及防备其它未被预料到的锁冲突。
     * 重试必须重开事务——已经失败的事务无法复用，
     * 而这里的 {@code action} 内部正是通过 {@code TransactionTemplate} 每次新开事务的。
     */
    private void runWithLockRetry(Runnable action) {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (PessimisticLockingFailureException ex) {
                attempt++;
                if (attempt >= PERSIST_MAX_ATTEMPTS) {
                    throw ex;
                }
                log.warn("ai_parse_persist_retry attempt={}/{} reason={}",
                        attempt, PERSIST_MAX_ATTEMPTS, rootMessage(ex));
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    /** 提交阶段就失败（如线程池被关停）时的兜底：把任务标失败，别让它永远停在 RUNNING。 */
    private void markTaskFailed(Long taskId, String message) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    taskRepository.findById(taskId).ifPresent(task -> {
                        task.setStatus(AiTaskStatus.FAILED);
                        task.setErrorMessage(message);
                        task.setFinishedAt(nowToSecond());
                        taskRepository.save(task);

                        // 课件状态也要跟着走，否则它停在 PARSING，
                        // 前端会一直显示「解析中」，而实际上没有任何线程在跑
                        Courseware courseware = task.getCourseware();
                        if (courseware != null && courseware.getStatus() == CoursewareStatus.PARSING) {
                            courseware.setStatus(CoursewareStatus.FAILED);
                            coursewareRepository.save(courseware);
                        }
                    }));
        } catch (Exception ex) {
            log.error("ai_parse_mark_failed_failed taskId={}", taskId, ex);
        }
    }

    /**
     * 解析模型返回的 JSON。
     *
     * <p>JSON 模式下正常不会带围栏，但模型偶尔仍会加 ``` 或前后多说一句，
     * 与其解析失败丢掉整整一页（那一页的模型钱已经花了），不如把外层剥干净再解。
     */
    private ParsedPage readModelJson(String raw, int pageNo) {
        String cleaned = stripToJsonObject(raw);
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            return new ParsedPage(
                    readStringList(root.path("knowledgePoints")),
                    readStringList(root.path("presetQuestions")));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "第 " + pageNo + " 页返回的不是合法 JSON：" + abbreviate(cleaned), ex);
        }
    }

    private static String stripToJsonObject(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.strip();
        }
        // 再兜一层：截取最外层的一对花括号，丢掉前后的多余说明
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            // 字段缺失或类型不对时返回空列表，而不是抛异常——
            // 「这一页没有知识点」是合法结果，不该算解析失败
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").strip();
            if (text.isEmpty()) {
                continue;
            }
            items.add(truncate(text, MAX_ITEM_CHARS));
            if (items.size() >= MAX_ITEMS_PER_PAGE) {
                break;
            }
        }
        return items;
    }

    private static boolean isUnfinished(AiTaskStatus status) {
        return status == AiTaskStatus.PENDING || status == AiTaskStatus.RUNNING;
    }

    /** 与 ClassSessionService 同一条规矩：DATETIME 只有秒精度，先截断再存，接口前后才一致。 */
    private static LocalDateTime nowToSecond() {
        return LocalDateTime.now().withNano(0);
    }

    /** 按码点截断，避免把 emoji 等代理对切成半个字符。 */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 取最内层异常的描述，避免日志里全是包装层的名字。 */
    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    /** 一页的解析结果。 */
    private record ParsedPage(List<String> knowledgePoints, List<String> presetQuestions) {

        boolean hasContent() {
            return !knowledgePoints.isEmpty() || !presetQuestions.isEmpty();
        }
    }
}
