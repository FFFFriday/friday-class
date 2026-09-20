package com.fridayclass.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 调用客户端 —— 两个智能体共用的<b>唯一</b>一层。
 *
 * <p>职责边界（对应设计文档 §1.2 的分层）：
 * <ul>
 *   <li>{@code Agent/Service} 负责「组装提示词 + 解析业务语义」；</li>
 *   <li><b>本类</b>只负责「发请求、超时、重试判定、截断处理、用量日志」。
 *       <b>不碰权限、不碰事务、不碰数据库</b>。</li>
 * </ul>
 *
 * <h3>为什么不用 OpenAI 官方 SDK 或引入新依赖</h3>
 * Spring Boot 3.5 自带的 {@code RestClient} 已经够用，多一个依赖就多一处版本冲突面。
 * 这个类总共不到 200 行，比读懂一个三方 SDK 的配置项更快。
 *
 * <h3>为什么建两个 RestClient</h3>
 * 超时配置挂在 {@code ClientHttpRequestFactory} 上，是<b>每个客户端</b>的，不能按请求改。
 * 而问答要 8 秒、解析要 60 秒，差 7.5 倍，只能各建一个。
 *
 * <h3>关于启动时校验 API Key</h3>
 * Key 缺失时<b>直接让应用启动失败</b>，而不是降级成假数据。
 * 这是刻意的：本项目已经被「界面显示成功、实际一个请求都没发」坑过一次
 * （见 {@code USE_MOCK} 那次），宁可启动不起来也不要静默假装能用。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    /** 连接超时对两种场景都用 5 秒：连不上就是连不上，与业务超时无关。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final String CHAT_PATH = "/chat/completions";

    /** 日志与异常里带上响应体的前若干个字符，便于定位，但不至于把日志刷爆。 */
    private static final int BODY_EXCERPT = 300;

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    /** 每个场景一个客户端，各自持有自己的读超时。 */
    private final Map<CallKind, RestClient> clients = new EnumMap<>(CallKind.class);

    public DeepSeekClient(ObjectMapper objectMapper,
                          @Value("${deepseek.api-key:}") String apiKey,
                          @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                          @Value("${deepseek.model:deepseek-flash}") String model) {
        this.objectMapper = objectMapper;
        this.model = (model == null || model.isBlank()) ? "deepseek-flash" : model.strip();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    未配置 DeepSeek API Key，AI 功能（F002 解析 / F004 问答）无法工作。

                    本地开发：在 backend/src/main/resources/application-local.yml 里写
                        deepseek:
                          api-key: "sk-..."
                    （该文件已在 .gitignore 中，不会进公开仓库。**不要**把 key 写进 application.yml。）

                    生产环境：用环境变量 DEEPSEEK_API_KEY 注入。
                    """);
        }
        this.apiKey = apiKey.strip();

        String base = stripTrailingSlash(baseUrl);
        for (CallKind kind : CallKind.values()) {
            clients.put(kind, buildClient(base, kind));
        }

        // 模型 ID 不在这里校验：实测传一个不存在的 ID（如 deepseek-v4-flash）时，
        // 服务端**不报错**，而是静默回退到 deepseek-flash，响应里的 model 字段会说明真相。
        // 所以只能靠日志里记下请求用的 ID，人工比对。
        log.info("deepseek_client_ready baseUrl={} model={} qaTimeout={}s parseTimeout={}s",
                base, this.model,
                CallKind.QA.singleTimeout().toSeconds(),
                CallKind.PARSE.singleTimeout().toSeconds());
    }

    /**
     * 调用模型。内部完成「传输层重试 + 截断升级」，调用方只需处理结果与最终失败。
     *
     * @param jsonMode 是否要求模型输出 JSON（仅解析用）。注意 DeepSeek 的 JSON 模式
     *                 <b>要求提示词里含有 "json" 字样</b>，{@link PromptTemplates#parsePage} 已满足。
     * @throws LlmException 全部尝试都失败；调用方按 {@link LlmException#isRetryable()} 决定文案
     */
    public LlmResult chat(CallKind kind, String systemPrompt, String userPrompt, boolean jsonMode) {
        int maxTokens = kind.initialMaxTokens();
        long backoffMs = kind.initialBackoffMs();
        long startNanos = System.nanoTime();
        LlmException lastFailure = null;

        for (int attempt = 1; attempt <= kind.maxAttempts(); attempt++) {
            // 总时限闸门。位置很关键：必须**在发起请求之前**判断。
            // 发起之后才发现预算不够，只能干等它超时——学生那边已经在看转圈了。
            if (attempt > 1 && !fitsBudget(kind, startNanos, backoffMs)) {
                log.warn("llm_budget_exhausted kind={} attempt={}/{} elapsedMs={}",
                        kind, attempt, kind.maxAttempts(), elapsedMs(startNanos));
                break;
            }

            LlmResult result;
            try {
                result = callOnce(kind, systemPrompt, userPrompt, maxTokens, jsonMode, attempt);
            } catch (LlmException ex) {
                if (!ex.isRetryable()) {
                    // 401/400/402 这类是配置问题，再试一百次也一样。立刻失败，别白等。
                    log.error("llm_abort_non_retryable kind={} status={} reason={}",
                            kind, ex.getHttpStatus(), ex.getMessage());
                    throw ex;
                }
                lastFailure = ex;
                log.warn("llm_attempt_failed kind={} attempt={}/{} status={} reason={}",
                        kind, attempt, kind.maxAttempts(), ex.getHttpStatus(), ex.getMessage());
                if (attempt < kind.maxAttempts()) {
                    sleep(backoffMs);
                    backoffMs *= 2;
                }
                continue;
            }

            if (result.usable()) {
                logUsage(kind, result);
                return result;
            }

            // 不截断却为空 —— 罕见，按可重试处理
            lastFailure = new LlmException(true, LlmException.NO_HTTP_STATUS,
                    result.truncated() ? "模型输出被截断" : "模型返回了空内容", null);

            boolean canEscalate = result.truncated()
                    && attempt < kind.maxAttempts()
                    && maxTokens < kind.maxTokensCap();

            if (canEscalate) {
                // 预算不够，不是服务端过载 —— 加大预算立刻重试，**不**退避等待（等也没用）。
                // 注意这里有上限：maxTokensCap 兜住，避免无限往上翻。
                maxTokens = Math.min(maxTokens * 2, kind.maxTokensCap());
                log.warn("llm_truncated_escalate kind={} attempt={} nextMaxTokens={}",
                        kind, attempt, maxTokens);
                continue;
            }

            if (attempt < kind.maxAttempts()) {
                sleep(backoffMs);
                backoffMs *= 2;
            }
        }

        throw lastFailure != null ? lastFailure
                : new LlmException(true, LlmException.NO_HTTP_STATUS, "模型调用失败", null);
    }

    /** 发一次请求。所有失败都翻译成 {@link LlmException}。 */
    private LlmResult callOnce(CallKind kind, String systemPrompt, String userPrompt,
                               int maxTokens, boolean jsonMode, int attempt) {
        Map<String, Object> body = buildBody(systemPrompt, userPrompt,
                kind.temperature(), maxTokens, jsonMode);

        long started = System.currentTimeMillis();
        String raw;
        try {
            raw = clients.get(kind).post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // 把服务端的错误说明摘出来，比 StackTrace 有用得多
            String detail = excerpt(ex.getResponseBodyAsString());
            throw new LlmException(LlmException.retryableStatus(status), status,
                    "HTTP " + status + " " + detail, ex);
        } catch (ResourceAccessException ex) {
            // 连接超时 / 读超时 / DNS 失败：请求可能根本没发出去，可重试
            throw new LlmException(true, LlmException.NO_HTTP_STATUS,
                    "网络异常：" + ex.getMessage(), ex);
        }

        long elapsedMs = System.currentTimeMillis() - started;
        return parseResponse(raw, maxTokens, attempt, elapsedMs);
    }

    private Map<String, Object> buildBody(String systemPrompt, String userPrompt,
                                          double temperature, int maxTokens, boolean jsonMode) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        // System 只放角色与硬约束；不可信内容一律走 user 消息里的数据区（见 PromptTemplates）
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    /**
     * 解析响应。用 Jackson 树模型而不是定义一堆 record：
     * 外部 API 的字段随时可能增删，树模型对「多了字段」无感，不会因为服务端加个字段就崩。
     */
    private LlmResult parseResponse(String raw, int maxTokens, int attempt, long elapsedMs) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException(true, LlmException.NO_HTTP_STATUS, "响应体为空", null);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception ex) {
            // 结构不对说明我们和服务端对不上，重试大概率还是同样的结果
            throw new LlmException(false, LlmException.NO_HTTP_STATUS,
                    "响应不是合法 JSON：" + excerpt(raw), ex);
        }

        // 少数情况下服务端会用 200 返回错误体
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").asText(excerpt(raw));
            throw new LlmException(false, LlmException.NO_HTTP_STATUS,
                    "服务端返回错误：" + message, null);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException(true, LlmException.NO_HTTP_STATUS,
                    "响应里没有 choices：" + excerpt(raw), null);
        }

        JsonNode choice = choices.get(0);
        String content = choice.path("message").path("content").asText("");
        String finishReason = choice.path("finish_reason").asText("");

        JsonNode usage = root.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);

        return new LlmResult(content, finishReason, promptTokens, completionTokens,
                reasoningTokens, maxTokens, attempt, elapsedMs);
    }

    /**
     * 用量日志 —— 设计文档 §5「可观测性」的落地。
     *
     * <p>成本敏感的项目如果不知道钱花在哪，就没法优化。
     * {@code reasoningTokens} 单列出来是因为它是<b>推理模型特有的隐藏开销</b>：
     * 它计入 {@code completionTokens}（也就是计费），但学生一个字都看不到。
     * 这个数字异常变大，说明页面文字太长或提示词让模型想太多了。
     */
    private void logUsage(CallKind kind, LlmResult result) {
        log.info("llm_usage kind={} model={} attempts={} maxTokens={} promptTokens={} "
                        + "completionTokens={} reasoningTokens={} finish={} elapsedMs={}",
                kind, model, result.attempts(), result.maxTokensUsed(),
                result.promptTokens(), result.completionTokens(), result.reasoningTokens(),
                result.finishReason(), result.elapsedMs());

        // 正文几乎全是思考、只剩一点点答案时提醒一句：多半是页面文字太长或提示词不够聚焦
        if (result.completionTokens() > 0
                && result.reasoningTokens() * 100 / result.completionTokens() > 90) {
            log.debug("llm_reasoning_heavy kind={} reasoning={}/{}",
                    kind, result.reasoningTokens(), result.completionTokens());
        }
    }

    /**
     * 建一个针对某种调用场景的 RestClient。
     *
     * <h3>⚠️ 关于本机「模型调用间歇性 Connect timed out」——已知问题，非代码缺陷</h3>
     *
     * 2026-09-21 实测确认，症状与结论如下：
     *
     * <p>症状：AI 回答变成「AI 助教暂时忙不过来」；日志里是
     * {@code Connect timed out}（或换成 JDK HttpClient 后的
     * {@code HTTP connect timed out}）；<b>而同一时刻 {@code curl} 打同一个地址
     * 只要 0.24 秒</b>。时好时坏，极像网络抖动。
     *
     * <p><b>真实原因</b>：{@code api.deepseek.com} 有<b>两条 A 记录</b>——
     * {@code 124.225.27.128} 与 {@code 171.105.220.186}。
     * 前者从本机网络<b>不可达</b>（TCP 连接直接超时），后者正常（约 66ms）。
     * 而 DNS 返回的<b>顺序会轮换</b>。于是坏地址排前面时必失败、排后面时一切正常。
     *
     * <p><b>两个内置客户端都不回落</b>（都实测过）：
     * <ul>
     *   <li>{@code HttpURLConnection}（默认的 {@code SimpleClientHttpRequestFactory}）：
     *       只用第一个地址，把连接超时放宽到 20 秒仍然失败；</li>
     *   <li>JDK 的 {@code java.net.http.HttpClient}：同样失败，每次都在
     *       {@code connectTimeout}（5 秒）耗尽后抛 {@code HttpConnectTimeoutException}。</li>
     * </ul>
     * 只有 {@code curl} 会做 Happy Eyeballs 自动回落，所以它一直正常——
     * <b>「curl 能通、程序不能通」正是这个问题的识别特征</b>。
     *
     * <p><b>这是本机到 DeepSeek 某台服务器的路由问题，应用层改不动。</b>
     * 可行的缓解手段：把可用地址固定进 hosts 文件，或换网络环境。
     * 排查命令见项目 README 的「Connect timed out 怎么办」。
     *
     * <p>⚠️ 排查时<b>别再往这两个方向想</b>（都是被证伪的旧结论）：
     * 「JVM 优先走 IPv6」（实测 {@code -Djava.net.preferIPv4Stack=true} 无效，已移除）、
     * 「换 JDK HttpClient 就好了」（实测无效，已回退）。
     */
    private RestClient buildClient(String baseUrl, CallKind kind) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(kind.singleTimeout());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    /**
     * 剩余时间够不够再发起一次尝试。
     *
     * <p>按<b>最坏情况</b>估算下一次尝试的开销：退避 + 连接超时 + 读超时。
     * 只有这笔账能装进总预算才继续，否则宁可现在就返回降级文案——
     * 反正真发出去也是超时，只是让学生多等十几秒。
     *
     * <p>注意 {@code budget == null}（解析场景）时永远返回 true：离线批处理不限时。
     */
    private boolean fitsBudget(CallKind kind, long startNanos, long backoffMs) {
        Duration budget = kind.totalBudget();
        if (budget == null) {
            return true;
        }
        long worstNextAttemptMs = backoffMs
                + CONNECT_TIMEOUT.toMillis()
                + kind.singleTimeout().toMillis();
        return elapsedMs(startNanos) + worstNextAttemptMs <= budget.toMillis();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com";
        }
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= BODY_EXCERPT ? oneLine : oneLine.substring(0, BODY_EXCERPT) + "…";
    }

    /**
     * 退避等待。
     *
     * <p>被中断时<b>必须恢复中断标志</b>再抛出，否则上层（如 Tomcat 线程池的关闭流程）
     * 收不到中断信号，会继续用已经废弃的线程干活。
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException(true, LlmException.NO_HTTP_STATUS, "等待重试时被中断", ex);
        }
    }
}
