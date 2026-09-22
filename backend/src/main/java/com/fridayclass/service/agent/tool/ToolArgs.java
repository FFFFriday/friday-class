package com.fridayclass.service.agent.tool;

import java.util.Map;

/**
 * 从模型给的参数里安全取值。
 *
 * <h3>为什么要包一层，而不是直接 cast</h3>
 *
 * 参数是模型生成的，<b>类型完全不可控</b>：它可能把 {@code pageFrom} 写成
 * 字符串 {@code "1"}、把数字写成 {@code "第三页"}、或者干脆漏掉。
 * 直接 {@code (Integer) args.get("pageFrom")} 会抛 {@code ClassCastException}，
 * 而那个异常会被当成「工具内部错误」—— 明明是模型给的参数不对，
 * 却报成服务端故障，排查方向从第一步就跑偏了。
 *
 * <p>所以这里统一「能转就转、转不了就返回 null」，由工具自己决定
 * 是当成「没传」还是回一句提示让模型改正。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    /**
     * 取<b>原文</b>字符串：<b>不做 strip</b>，只把全空白当成「没传」。
     *
     * <p>文件正文必须走这个而不是 {@link #string}：Markdown 的缩进、
     * 行尾空格都是有意义的（列表嵌套、换行语义），strip 一下可能把
     * 整篇文档的结构改掉。而 {@link #string} 是给「文件名」「关键词」
     * 这类本来就该去掉首尾空白的短参数用的。
     */
    public static String text(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value);
        return raw.isBlank() ? null : raw;
    }

    /** 取字符串；空白视为「没传」，返回 null。 */
    public static String string(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * 取整数。
     *
     * <p>接受 {@code 3}、{@code "3"}、{@code 3.0} 三种形态 ——
     * 模型把整数写成 {@code 3.0} 是常事，为此报错太苛刻。
     * 真的转不出来（比如 {@code "第三页"}）时返回 null。
     */
    public static Integer integer(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 取正整数；{@code <= 0} 视为没传。 */
    public static Integer positiveInteger(Map<String, Object> args, String key) {
        Integer value = integer(args, key);
        return (value == null || value <= 0) ? null : value;
    }
}
