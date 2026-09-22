package com.fridayclass.llm;

import java.time.Duration;

/**
 * 调用场景 —— 把「两个智能体各自独立的超时、重试与输出预算」写成<b>一处</b>声明。
 *
 * <p>文档 §3.4 专门修正过 V1 的一个自相矛盾：V1 一边写「超时 20 秒」、一边写「重试 3 次指数退避」，
 * 最坏 {@code 3×20 + 7 ≈ 67 秒}，而同一张表刚说过「课堂等待超过 20 秒不可接受」。
 * 根因是<b>解析和问答被当成了同一件事</b>——它们的预算根本不该一样。
 *
 * <h3>为什么 max_tokens 要单独按场景定，而且比「够用」大得多</h3>
 *
 * {@code deepseek-flash} 是<b>推理模型</b>：思考过程计入 {@code completion_tokens}（计费）
 * 但不出现在正文里。实测同一段文字的 reasoning tokens 是 150 / 184 / 1163 —— <b>方差极大</b>，
 * 且<b>不是</b>按预算成比例增长（给 2000 只用了 184，给 1200 却用满 1200 后被截断）。
 *
 * <p>所以策略是：<b>初始值给足，遇到截断再加倍</b>。
 * 给大预算<b>不会</b>多花钱——计费按实际生成的 token 算，不按上限算，
 * 多出来的只是「万一模型这次想得多」的保险。
 * 而给少了会得到 HTTP 200 + 空正文，是最难查的静默失败。
 */
public enum CallKind {

    /** 学生问答（F004）。在线场景，总预算 ≤ 20 秒（含连接、退避、所有重试）。 */
    QA(Duration.ofSeconds(8), 2, 1_000L, 0.5, 2_000, 4_000, Duration.ofSeconds(20)),

    /**
     * 课件解析（F002）。离线批处理，允许慢，不允许丢页。
     *
     * <p>初始 4000、上限 8000 是 2026-09-15 用真实课件（69 页）跑出来调过的：
     * 初版按探针数据定了 2000，结果 69 页里有 <b>4 页真的被截断</b>并触发升级重试——
     * 真实课件的文字量比探针大得多，reasoning 平均 674、最高 2839。
     * 直接给足比「截断后再重试」便宜：重试要多发一次请求，而给大上限本身不花钱
     * （计费按实际生成的 token 算，不按上限算）。
     */
    PARSE(Duration.ofSeconds(60), 4, 1_000L, 0.3, 4_000, 8_000, null),

    /**
     * 课堂总结（M5）。后台异步，学生不在等，所以可以让它慢一点。
     *
     * <p>与解析的区别在于<b>温度压到 0.2</b>：总结是「整理已经发生过的事」，
     * 要的是稳定、忠实，不是文采。温度高一点它就开始替你「润色」，
     * 而需求明确要求<b>不得编造记录里没有的内容</b>。
     *
     * <p>总预算 3 分钟：比解析的「不限」紧，比问答的 20 秒松——
     * 一节课的记录可能很长，但也不能挂到天荒地老（前端在轮询状态）。
     */
    SUMMARY(Duration.ofSeconds(60), 2, 1_000L, 0.2, 4_000, 8_000, Duration.ofMinutes(3)),

    /**
     * AI 智能体的一步（F005+，见「修改文档2/A2」）。
     *
     * <p><b>与前三者的本质区别：这是第一次「模型能产生副作用」的调用。</b>
     * 前三者模型只输出文字；这里模型会点名要调哪个工具、写哪个文件。
     *
     * <h3>为什么总预算不限（{@code totalBudget = null}）</h3>
     *
     * 前三个场景的单次调用就是全部工作，所以「总预算」有意义。
     * 而智能体是<b>最多 6 步的循环</b>，总时长由 {@code AgentLoop} 的轮数闸门管，
     * 不归单次调用管。若在这里也压一个 20 秒总预算，
     * 就会出现「第 3 步跑到一半被判超时」这种和真实时限无关的失败。
     *
     * <h3>为什么 max_tokens 初始就给 4000</h3>
     *
     * 与解析同理：这一轮模型既要想，又可能要吐出一整篇文档（write_file 的 content）。
     * 给少了会得到 HTTP 200 + 空正文 + {@code finish_reason=length}，
     * 那是最难查的静默失败。计费按<b>实际生成</b>的 token 算，给大上限本身不花钱。
     *
     * <h3>为什么温度 0.3</h3>
     *
     * 智能体干的是「查资料 → 整理成文」，不是创作。温度高一点它就开始自由发挥，
     * 而输出是要交给老师用的材料。取 0.3 略高于总结（0.2）、远低于问答（0.5）。
     */
    AGENT(Duration.ofSeconds(90), 2, 1_000L, 0.3, 4_000, 8_000, null);

    private final Duration singleTimeout;
    /** 最多尝试次数，<b>含首次</b>。2 = 首发 + 1 次重试。 */
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double temperature;
    private final int initialMaxTokens;
    /** 截断后加倍的上限，防止无限往上翻。 */
    private final int maxTokensCap;
    /**
     * 整个调用的总时限，<b>含建连、退避与所有重试</b>；{@code null} 表示不限。
     *
     * <p>没有它的话，「≤20 秒」就只是个愿望：单次最坏 = 连接超时 5 秒 + 读超时 8 秒 = 13 秒，
     * 重试一次加 1 秒退避就是 27 秒，<b>直接违反设计文档 §3.4 的总预算</b>。
     * 光靠「单次超时 × 次数」并不能约束总量。
     */
    private final Duration totalBudget;

    CallKind(Duration singleTimeout, int maxAttempts, long initialBackoffMs,
             double temperature, int initialMaxTokens, int maxTokensCap, Duration totalBudget) {
        this.singleTimeout = singleTimeout;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.temperature = temperature;
        this.initialMaxTokens = initialMaxTokens;
        this.maxTokensCap = maxTokensCap;
        this.totalBudget = totalBudget;
    }

    public Duration singleTimeout() {
        return singleTimeout;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long initialBackoffMs() {
        return initialBackoffMs;
    }

    public double temperature() {
        return temperature;
    }

    public int initialMaxTokens() {
        return initialMaxTokens;
    }

    public int maxTokensCap() {
        return maxTokensCap;
    }

    /** 总时限；{@code null} 表示不限时（离线解析）。 */
    public Duration totalBudget() {
        return totalBudget;
    }
}
