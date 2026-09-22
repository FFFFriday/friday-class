package com.fridayclass.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 解析用的线程池。
 *
 * <h3>为什么需要它，以及为什么只有一个池</h3>
 *
 * 一份 79 页的课件要调 79 次模型。实测单次约 2 秒，顺序跑要 <b>2.5 分钟以上</b>；
 * 并发 3 路大约 1 分钟。所以页级并发是必须的。
 *
 * <p>但<b>不能</b>再额外加一个「每份课件一个协调线程」的池：协调线程要等所有页跑完，
 * 于是它自己会占住一个池线程并阻塞，很容易写出「池被占满、
 * 页任务排不进去、协调线程永远等不到」的死锁。
 *
 * <p>这里的做法是<b>把「等」彻底去掉</b>：所有页任务一次性提交进同一个池，
 * 每个任务跑完把剩余计数减一，<b>减到 0 的那个任务负责收尾</b>（写任务状态、改课件状态）。
 * 没有任何线程在等待，所以无论池多大都不会死锁。
 *
 * <p>池大小即「同时进行的模型调用数」的全局上限——多个课件同时解析时共享这个上限，
 * 不会因为上传了三份课件就打到三倍并发。
 */
@Configuration
public class AiExecutorConfig {

    /**
     * @param pageConcurrency 同时进行的模型调用数。默认 3：
     *                        再高对单份课件的提速已经不明显，反而更容易触发服务端限流（429）。
     */
    @Bean(name = "aiParseExecutor")
    public ThreadPoolTaskExecutor aiParseExecutor(
            @Value("${app.ai.page-concurrency:3}") int pageConcurrency) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, pageConcurrency);
        // core 与 max 相等：不希望池按负载伸缩，并发数应当是可预测的
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        // 队列无界是**刻意**的：页任务是一次性全部提交的，一份 500 页的课件就有 500 个任务。
        // 队列有界的话超出的部分会被拒绝（RejectedExecutionException），
        // 而那些页永远跑不到，任务计数就永远减不到 0。
        // 任务对象极小（就是「课件 ID + 页 ID」两个引用），排队本身不占多少内存。
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("ai-parse-");
        // 关停时等在跑的页跑完，避免「模型已经答完、结果却没写库」这种花了钱又丢结果的情况
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 课堂总结专用池（M5）。<b>单线程，且刻意不与解析池共用。</b>
     *
     * <h3>为什么必须分开</h3>
     * 解析池的大小就是「同时进行的模型调用数」的全局上限（默认 3）。
     * 如果总结挤进同一个池：老师正在解析一份百页课件时点「生成总结」，
     * 总结任务会排在几十个页任务后面——界面上就是「点了没反应」，
     * 而它的状态一直是 PENDING，看起来像卡死了。
     *
     * <h3>为什么单线程</h3>
     * 总结是低频的人工操作，同一时刻有多个请求的收益极小；
     * 单线程还能保证「同一份记录不会被两个线程同时写进 course_summary」
     * （那张表上 session_id 有唯一键，并发写会直接撞键）。
     *
     * <p>队列无界，理由同解析池：任务对象极小（几个 ID），
     * 而有界队列一旦满了就会抛 RejectedExecutionException——
     * 那条总结会永远停在 PENDING，前端会一直转圈。
     */
    @Bean(name = "summaryExecutor")
    public ThreadPoolTaskExecutor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("summary-");
        // 关停时等在跑的那一篇写完：一次调用已经花了钱，结果不能丢
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * AI 智能体任务专用池。
     *
     * <h3>为什么也必须分开，而且线程数很小</h3>
     *
     * 三类负载的形状完全不同：
     * <ul>
     *   <li>解析：一次提交几十上百个小任务，每个几秒，跑完就走；</li>
     *   <li>总结：一次一个，长但可等待；</li>
     *   <li><b>智能体：一次任务要连续调 3~7 次模型，中间还夹着读写磁盘</b>，
     *       单个任务就占住一个线程十几秒到一分钟。</li>
     * </ul>
     *
     * <p>如果它挤进解析池：老师点一次「帮我整理复习资料」，
     * 解析池里的 3 个线程全被占住，<b>此时另一个老师正在解析的百页课件会直接停住</b>——
     * 而停住的原因是「有人在用智能体」，从界面上完全看不出来。
     *
     * <h3>为什么默认只有 2 个线程</h3>
     *
     * 智能体是人工触发的高成本操作，不会有人同时开十个；
     * 而且每个任务都在烧 token。2 是「够两个人同时用」与「不会瞬间打出一串付费请求」
     * 之间的折中。队列无界，理由同上面两个池：宁可排队，也不要
     * {@code RejectedExecutionException} —— 那会让任务永远停在 RUNNING，
     * 前端一直转圈。
     */
    @Bean(name = "agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(
            @Value("${app.ai.agent-concurrency:2}") int concurrency) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(1, concurrency);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("ai-agent-");
        // 关停时等在跑的任务收尾：任务记录已经落库了，
        // 中途被杀会让它永远停在 RUNNING，前端一直轮询一个死任务
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
