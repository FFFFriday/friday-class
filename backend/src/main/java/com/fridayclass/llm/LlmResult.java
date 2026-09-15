package com.fridayclass.llm;

/**
 * 一次模型调用的结果。
 *
 * <p><b>为什么要带 {@code finishReason} 而不只返回文本</b>：
 * {@code deepseek-flash} 是<b>推理模型</b>，它把预算花在「思考」上，思考的内容也计入
 * {@code completion_tokens} 计费，但<b>不会出现在 {@code content} 里</b>。
 * 如果 {@code max_tokens} 给少了，预算会被思考全部吃光，此时接口返回 HTTP 200、
 * {@code content} 是空字符串、{@code finish_reason} 是 {@code "length"}——
 * <b>不报错，但什么也没答</b>。这是最难查的一类失败，必须显式识别。
 *
 * <p>实测数据（2026-09-15，同一段题库文字）：
 * <pre>
 *   max_tokens=400  → completion=400  reasoning=400  finish=length  content=""（空）
 *   max_tokens=800  → completion=279  reasoning=150  finish=stop    content=完整 JSON
 *   max_tokens=1200 → completion=1200 reasoning=1163 finish=length  content=截断
 * </pre>
 * 注意 800 成功而 1200 失败——推理长度是<b>随机</b>的，所以不能指望「给够一次就行」，
 * 只能靠 {@link #truncated()} 判定后再加大预算重试。
 */
public record LlmResult(
        String content,
        String finishReason,
        int promptTokens,
        int completionTokens,
        int reasoningTokens,
        /** 本次实际使用的 max_tokens（重试会加大，便于排查）。 */
        int maxTokensUsed,
        /** 实际发起的请求次数（含重试）。 */
        int attempts,
        long elapsedMs) {

    /** 模型输出被截断（预算耗尽，正文没写完）。 */
    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason);
    }

    /** 正文为空或全是空白。 */
    public boolean blank() {
        return content == null || content.isBlank();
    }

    /** 结果可用：既没截断，也不为空。 */
    public boolean usable() {
        return !truncated() && !blank();
    }
}
