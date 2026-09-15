package com.fridayclass.enums;

/**
 * 问答状态。对应 {@code qa_record.status} 字段，同时用于接口响应的 {@code status} 字段。
 *
 * <p>三个值的分工（前两个会落库，第三个**永不落库**）：
 * <ul>
 *   <li>{@link #SUCCESS} —— 模型正常作答；</li>
 *   <li>{@link #FAILED} —— 模型调用超时 / 失败，{@code answer} 是友好提示而非答案。
 *       仍然落库，因为 F006 学情统计要能看出「这次没答上」；</li>
 *   <li>{@link #SKIPPED} —— <b>根本没调模型</b>：课件还在解析中 / 解析失败 / 本页确实没有知识点。
 *       这三种情况返回给学生看的是一句话提示，<b>不写入 {@code qa_record}</b>——
 *       写进去会把「没问成」记成「问过了」，污染 F006 统计，
 *       也会让学生刷新后看到一条根本不属于自己问答历史的记录。</li>
 * </ul>
 *
 * <p>前端按这个字段区分渲染：{@code FAILED} 用红色错误样式；
 * {@code SKIPPED} 用中性提示样式，并且**不追加到问答列表**，只作为临时提示显示。
 */
public enum QaStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
