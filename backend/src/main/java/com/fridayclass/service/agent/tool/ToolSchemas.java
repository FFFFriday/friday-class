package com.fridayclass.service.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼工具参数 Schema 的小工具。
 *
 * <p>直接用 {@code Map.of} 拼三层嵌套的 JSON Schema 会得到一坨
 * 括号对不上的东西，而且 {@code Map.of} 最多只接受 10 组键值。
 * 这几个方法把结构固定下来，让工具类里只剩「参数叫什么」。
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** 一个 object 类型的 schema。{@code required} 为 null 表示没有必填项。 */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // 没有必填项时不下发 required 字段：空数组在某些实现里会被当成语法错误
        if (required != null && !required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return schema;
    }

    /** 字符串参数。 */
    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** 整数参数。 */
    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
