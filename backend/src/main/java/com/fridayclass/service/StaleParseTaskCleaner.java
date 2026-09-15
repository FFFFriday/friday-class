package com.fridayclass.service;

import com.fridayclass.entity.AiParseTask;
import com.fridayclass.entity.Courseware;
import com.fridayclass.enums.AiTaskStatus;
import com.fridayclass.enums.CoursewareStatus;
import com.fridayclass.repository.AiParseTaskRepository;
import com.fridayclass.repository.CoursewareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时清理「上一次进程留下的僵尸解析任务」。
 *
 * <h3>为什么必须有这个类</h3>
 *
 * 解析是<b>内存里跑</b>的后台任务，而任务状态记在数据库里。两者在进程重启时会脱节：
 * 数据库里还写着 {@code RUNNING}，但真正在跑它的线程已经随进程消失了。
 *
 * <p>不管的话会有<b>两个</b>后果，第二个尤其隐蔽：
 * <ol>
 *   <li>前端永远看到「解析中 40%」转圈，刷新多少次都不动——因为没人再推进它了；</li>
 *   <li>更糟的是：{@link AiParseService} 在数据库层面检查「有没有未结束的任务」来防重复触发，
 *       这条僵尸记录会让这份课件<b>永远无法再次解析</b>，点多少次都是「正在解析中」。
 *       内存锁在重启时已经清空了，所以拦住的是数据库那道检查——用户完全看不出原因。</li>
 * </ol>
 *
 * <p>所以这里在启动时把 PENDING / RUNNING 的任务一律标记为 {@code FAILED}
 * 并写清原因，让老师能直接重新点「AI 解析」。课件状态同步改成 {@code FAILED}，
 * 与设计文档 §2.3 的状态表一致（全部页失败 → 课件 FAILED，重试入口据此触发）。
 */
@Component
public class StaleParseTaskCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleParseTaskCleaner.class);

    private static final String STALE_MESSAGE = "服务重启导致解析中断，请重新解析";

    private final AiParseTaskRepository taskRepository;
    private final CoursewareRepository coursewareRepository;

    public StaleParseTaskCleaner(AiParseTaskRepository taskRepository,
                                 CoursewareRepository coursewareRepository) {
        this.taskRepository = taskRepository;
        this.coursewareRepository = coursewareRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AiParseTask> stale = taskRepository.findByStatusIn(
                List.of(AiTaskStatus.PENDING, AiTaskStatus.RUNNING));

        if (stale.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (AiParseTask task : stale) {
            task.setStatus(AiTaskStatus.FAILED);
            task.setErrorMessage(STALE_MESSAGE);
            // finishedAt 用当前时间：这些任务确实是在「这一次启动」被判定为终止的
            task.setFinishedAt(now);

            Courseware courseware = task.getCourseware();
            if (courseware != null && courseware.getStatus() == CoursewareStatus.PARSING) {
                courseware.setStatus(CoursewareStatus.FAILED);
                coursewareRepository.save(courseware);
            }
            log.warn("stale_parse_task_recovered taskId={} coursewareId={}",
                    task.getId(), courseware == null ? null : courseware.getId());
        }

        taskRepository.saveAll(stale);
        log.warn("stale_parse_tasks_cleaned count={}", stale.size());
    }
}
