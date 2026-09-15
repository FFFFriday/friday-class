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
}
