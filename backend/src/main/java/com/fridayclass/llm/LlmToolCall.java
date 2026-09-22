package com.fridayclass.llm;

/**
 * 模型要求调用的一次工具。
 *
 * <p>对应响应里 {@code choices[0].message.tool_calls[n]} 这一项：
 * <pre>
 * {
 *   "id": "call_00_abc",
 *   "type": "function",
 *   "function": { "name": "write_file", "arguments": "{\"filename\":\"x.md\"}" }
 * }
 * </pre>
 *
 * <h3>为什么 {@code arguments} 保留成原始字符串，不在这里解析成 Map</h3>
 *
 * <b>解析失败要能被当成一次「模型犯错」来处理，而不是在这里抛异常。</b>
 * 模型偶尔会吐出不合法的 JSON（尤其被截断时）。如果这里 eagerly 解析，
 * 一次坏参数就会掀翻整个六轮循环；保留原文，调用方才能回一句
 * 「你的参数不是合法 JSON，请重试」并继续跑下一轮。
 *
 * <p>另外这个字符串要**原样回填**到下一轮的 assistant 消息里 —— OpenAI 兼容协议
 * 要求把 tool_calls 一字不差地带回上下文，重新序列化反而可能对不上。
 */
public record LlmToolCall(String id, String name, String arguments) {
}
