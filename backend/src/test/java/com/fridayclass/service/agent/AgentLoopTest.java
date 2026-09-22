package com.fridayclass.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fridayclass.enums.AgentRunStatus;
import com.fridayclass.enums.FileFormat;
import com.fridayclass.llm.CallKind;
import com.fridayclass.llm.DeepSeekClient;
import com.fridayclass.llm.LlmResult;
import com.fridayclass.llm.LlmToolCall;
import com.fridayclass.service.agent.tool.AgentTool;
import com.fridayclass.service.agent.tool.AgentToolRegistry;
import com.fridayclass.service.agent.tool.ToolSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多步循环的边界用例。
 *
 * <h3>为什么不靠真实模型来验这个</h3>
 *
 * 起初我用「叫它一页一页读」的方式逼真实模型跑满 6 轮。后来提示词里写清
 * 「最多 6 次工具调用、请一次查全」之后，<b>模型直接把 5 次分页读取合并成 1 次</b>，
 * 上限再也撞不到 —— 测试从「通过」变成「失败」，而代码其实变得更好了。
 *
 * <p>教训：<b>用真实模型验闸门，测的是模型的心情，不是代码。</b>
 * 所以这里把轮数上限做成可注入的（默认 6），用上限 2 的实例精确构造边界。
 */
class AgentLoopTest {

    /** 本测试里的轮数上限。取小值，两三轮就能撞到边界。 */
    private static final int MAX_STEPS = 2;

    private DeepSeekClient client;
    private List<String> executedTools;
    private AgentLoop loop;
    private AgentToolContext context;

    @BeforeEach
    void setUp() {
        client = mock(DeepSeekClient.class);
        executedTools = new ArrayList<>();

        AgentTool echo = new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "测试用工具：记一笔调用并回一句话。";
            }

            @Override
            public Map<String, Object> parameters() {
                return ToolSchemas.object(Map.of(), null);
            }

            @Override
            public String execute(AgentToolContext ctx, Map<String, Object> args) {
                executedTools.add("echo");
                return "工具结果";
            }
        };

        AgentToolRegistry registry = new AgentToolRegistry(List.of(echo));
        loop = new AgentLoop(client, registry, new ObjectMapper(), MAX_STEPS);
        context = new AgentToolContext(1L, "我的资料", FileFormat.MD, null, null, "写点什么", null);
    }

    @Test
    @DisplayName("模型直接给文字 → SUCCESS，一次工具都没调")
    void answersImmediately() {
        when(client.chatWithTools(any(), any(), any())).thenReturn(text("做完了"));

        AgentLoop.Outcome outcome = loop.run(context, "写点什么");

        assertEquals(AgentRunStatus.SUCCESS, outcome.status());
        assertEquals(0, outcome.rounds(), "一轮工具都没用");
        assertTrue(executedTools.isEmpty());
        assertEquals("做完了", outcome.text());
    }

    @Test
    @DisplayName("调一次工具后给文字 → SUCCESS，轮数记 1")
    void answersAfterOneToolRound() {
        when(client.chatWithTools(any(), any(), any()))
                .thenReturn(toolCall("call_1"), text("已经写好了"));

        AgentLoop.Outcome outcome = loop.run(context, "写点什么");

        assertEquals(AgentRunStatus.SUCCESS, outcome.status());
        assertEquals(1, outcome.rounds());
        assertEquals(List.of("echo"), outcome.usedTools());
    }

    @Test
    @DisplayName("用满上限那一步的成果算数：第 2 轮写完就答 → SUCCESS，不是 LIMIT")
    void completingOnTheLastAllowedRoundIsSuccess() {
        // 这条正是被修掉的那个 bug：原实现「跑满 2 轮就退出」，
        // 于是「恰好在最后一轮把活干完」的任务会被报成「还没全部完成」，
        // 老师看到橙色警告以为白跑了，重跑一次、再付一次钱。
        when(client.chatWithTools(any(), any(), any()))
                .thenReturn(toolCall("call_1"), toolCall("call_2"), text("我用满了轮数，但活干完了"));

        AgentLoop.Outcome outcome = loop.run(context, "写点什么");

        assertEquals(AgentRunStatus.SUCCESS, outcome.status(), "干完了就该是 SUCCESS");
        assertEquals(2, outcome.rounds(), "两轮都用上了");
        assertEquals(2, executedTools.size(), "两轮的工具都执行了");
    }

    @Test
    @DisplayName("用满上限还想要第 3 轮 → LIMIT，且第 3 轮的工具**不会被执行**")
    void exceedsLimit() {
        when(client.chatWithTools(any(), any(), any()))
                .thenReturn(toolCall("call_1"), toolCall("call_2"), toolCall("call_3"), text("收尾说明"));

        AgentLoop.Outcome outcome = loop.run(context, "写点什么");

        assertEquals(AgentRunStatus.LIMIT, outcome.status());
        assertEquals(2, outcome.rounds(), "只算真正执行了的轮数");
        assertEquals(2, executedTools.size(),
                "第 3 轮的工具必须被闸门挡住——执行了就突破了上限");
        assertTrue(outcome.text().contains("上限"), "提示里要说明是步数用尽：" + outcome.text());
        assertFalse(outcome.text().isBlank(), "不能是空提示（静默截断）");
    }

    @Test
    @DisplayName("用量按累计记：三次尝试的 token 都算进去，不是只记最后一次")
    void accumulatesTokensAcrossRounds() {
        when(client.chatWithTools(any(), any(), any()))
                .thenReturn(toolCall("call_1"), toolCall("call_2"), text("好了"));

        AgentLoop.Outcome outcome = loop.run(context, "写点什么");

        // 每个桩结果自带 prompt=10 / completion=5，三轮共 30 / 15
        assertEquals(30, outcome.promptTokens());
        assertEquals(15, outcome.completionTokens());
    }

    @Test
    @DisplayName("达到上限时仍然只调用模型一次收尾（不额外执行工具）")
    void wrapUpCallHasNoTools() {
        when(client.chatWithTools(any(), any(), any()))
                .thenReturn(toolCall("call_1"), toolCall("call_2"), toolCall("call_3"), text("收尾"));

        loop.run(context, "写点什么");

        // 1、2 轮带工具 + 第 3 次（判定超限的那次）带工具 + 1 次不带工具的收尾 = 4 次
        verify(client, times(4)).chatWithTools(any(), any(), any());
    }

    // ── 桩 ────────────────────────────────────────────────────

    /** 一次「模型只说了话」的响应。 */
    private static LlmResult text(String content) {
        return new LlmResult(content, "stop", 10, 5, 0, 100, 1, 20L, List.of());
    }

    /** 一次「模型要求调工具」的响应。正文为空是常态。 */
    private static LlmResult toolCall(String id) {
        return new LlmResult("", "tool_calls", 10, 5, 0, 100, 1, 20L,
                List.of(new LlmToolCall(id, "echo", "{}")));
    }
}
