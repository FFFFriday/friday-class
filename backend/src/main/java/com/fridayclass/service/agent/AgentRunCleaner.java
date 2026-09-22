package com.fridayclass.service.agent;

import com.fridayclass.entity.AiAgentRun;
import com.fridayclass.enums.AgentRunStatus;
import com.fridayclass.repository.AiAgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 启动时清理「上一次进程留下的僵尸智能体任务」。
 *
 * <h3>为什么必须有</h3>
 *
 * 智能体任务是<b>在内存里跑</b>的（线程池 + 六轮循环），而状态记在数据库里。
 * 进程重启时两者会脱节：库里还写着 {@code RUNNING}，但跑它的线程已经没了。
 *
 * <p>后果很具体：<b>老师那一页会永远转圈</b>。前端每 1.5 秒轮询一次，
 * 每次都拿到 {@code RUNNING}，它会一直等下去 —— 刷新页面也一样，
 * 因为「正在运行」这个事实就写在库里。而老师此时唯一能想到的动作是
 * <b>再点一次「执行」</b>，于是又发起一个付费任务。
 *
 * <p>所以启动时一律把它们标成 {@code FAILED} 并写清原因，
 * 让老师看到「服务重启导致任务中断」这句能理解的话，然后重新发起。
 *
 * <p>与 {@code StaleParseTaskCleaner} 同一个立场：<b>内存态与库态脱节的地方，
 * 必须在启动时对账。</b>
 */
@Component
public class AgentRunCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunCleaner.class);

    private static final String STALE_MESSAGE = "服务重启导致任务中断，请重新发起";

    private final AiAgentRunRepository runRepository;

    public AgentRunCleaner(AiAgentRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AiAgentRun> stale = runRepository.findByStatus(AgentRunStatus.RUNNING);
        if (stale.isEmpty()) {
            return;
        }
        for (AiAgentRun run : stale) {
            run.setStatus(AgentRunStatus.FAILED);
            run.setError(STALE_MESSAGE);
            log.warn("stale_agent_run_recovered runId={} ownerId={} instruction={}",
                    run.getId(), run.getOwnerId(), sanitizeForLog(run.getInstruction()));
        }
        runRepository.saveAll(stale);
        log.warn("stale_agent_runs_cleaned count={}", stale.size());
    }

    /**
     * 把自由文本压成一行再进日志。
     *
     * <p>{@code instruction} 是老师（或任何能调这个接口的人）写的自由文本，
     * 里面可以有换行。原样打出来的话，一行日志能被伪造成好几行 ——
     * 查日志的人分不清哪一行是真的。
     *
     * <p>注意文件名那条路径<em>不</em>需要这个：沙箱已经拒掉了控制字符。
     * 这里是**唯一**把未经校验的自由文本写进日志的地方。
     */
    private static String sanitizeForLog(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("[\\r\\n\\t]+", " ").strip();
    }
}
