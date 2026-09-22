package com.fridayclass.service.agent.tool;

import com.fridayclass.common.BusinessException;
import com.fridayclass.service.agent.AgentToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把全部 {@link AgentTool} 收进来，并按上下文<b>裁剪</b>后发给模型。
 *
 * <h2>「动态裁剪」到底值在哪</h2>
 *
 * 老师没选课堂时，{@code get_class_records} <b>根本不出现在工具列表里</b>。
 * 对比「照给、参数为空就报错」：
 * <ul>
 *   <li>模型看不见这个能力，就不会去调它，也不会<b>瞎猜一个课堂 id 填进去</b>；</li>
 *   <li>工具越多，模型选错的概率越大。裁剪掉一半，选择质量立刻上升；</li>
 *   <li>发的工具定义本身也占 token，每次调用都要付一遍。</li>
 * </ul>
 *
 * <h2>执行时再校验一次可用性</h2>
 *
 * {@link #execute} 会重新检查 {@code availableIn}。模型<b>可能幻觉出一个
 * 存在但这次没给它的工具名</b>（工具名在训练数据里很常见）。
 * 不查的话，就会出现「说了没有查记录的能力，结果还是查了」这种
 * 与设计文档直接矛盾的后果。
 */
@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final Map<String, AgentTool> byName;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            AgentTool previous = map.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名重复：" + tool.name()
                        + "（" + previous.getClass().getName() + " 与 " + tool.getClass().getName() + "）");
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("没有任何 AgentTool 实现，AI 智能体无法工作");
        }
        // 保持注册顺序（LinkedHashMap）而不是 Map.copyOf：发给模型的工具顺序稳定，
        // 出问题时两次请求的提示词才能逐字对比。
        this.byName = Collections.unmodifiableMap(map);
        log.info("agent_tools_ready tools={}", map.keySet());
    }

    /** 本次任务要发给模型的工具定义（OpenAI 兼容格式）。 */
    public List<Map<String, Object>> definitionsFor(AgentToolContext context) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (AgentTool tool : byName.values()) {
            if (!tool.availableIn(context)) {
                continue;
            }
            definitions.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameters())));
        }
        return definitions;
    }

    /** 本次任务实际会给的工具名，用于日志与验收（「不选课堂就没有查记录」要能被验出来）。 */
    public List<String> availableNames(AgentToolContext context) {
        return byName.values().stream()
                .filter(tool -> tool.availableIn(context))
                .map(AgentTool::name)
                .toList();
    }

    /**
     * 执行一个工具。
     *
     * @throws BusinessException 工具名不存在、或这次没把这个工具给模型
     */
    public String execute(AgentToolContext context, String name, Map<String, Object> args) {
        AgentTool tool = byName.get(name);
        if (tool == null) {
            log.warn("agent_tool_unknown name={} available={}", name, byName.keySet());
            throw new BusinessException(400, "没有名为「" + name + "」的工具");
        }
        if (!tool.availableIn(context)) {
            log.warn("agent_tool_not_offered name={} userId={}", name, context.userId());
            throw new BusinessException(400, "本次任务不能使用工具「" + name + "」（没有选择它需要的上下文）");
        }
        return tool.execute(context, args);
    }
}
