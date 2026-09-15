package com.fridayclass.llm;

/**
 * 模型调用异常。
 *
 * <p><b>{@link #isRetryable()} 是全部意义所在。</b>文档 §2.4 明确指出：
 * 401 鉴权失败 / 400 参数错 / 402 余额不足属于<b>配置问题</b>，重试一百次也是同样的结果，
 * 只会把「配置写错了」放大成每次白等两分钟。而超时、429、5xx 是<b>临时故障</b>，值得重试。
 *
 * <p>分类规则：
 * <ul>
 *   <li>HTTP 429（限流）、5xx（服务端故障）—— <b>可重试</b></li>
 *   <li>连接超时 / 读超时 / DNS 失败（{@code ResourceAccessException}）—— <b>可重试</b></li>
 *   <li>HTTP 401 / 400 / 402、响应体不是预期结构 —— <b>不可重试</b></li>
 * </ul>
 *
 * <p>这里的 message 是<b>给开发看的技术描述</b>，不要直接甩给学生——
 * 学生看到的降级文案由 Service 决定（「AI 助教暂时忙不过来，请稍后再试」）。
 */
public class LlmException extends RuntimeException {

    /** 连接层失败（请求根本没发出去）时用的状态码占位。 */
    public static final int NO_HTTP_STATUS = 0;

    private final boolean retryable;
    /** HTTP 状态码；{@link #NO_HTTP_STATUS} 表示请求根本没发出去。 */
    private final int httpStatus;

    public LlmException(boolean retryable, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 按 HTTP 状态码判定；{@link #NO_HTTP_STATUS} 视为连接层失败，可重试。 */
    public static boolean retryableStatus(int status) {
        if (status == NO_HTTP_STATUS) {
            return true;
        }
        return status == 429 || status >= 500;
    }
}
