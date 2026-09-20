package com.fridayclass.enums;

/**
 * 课程总结生成状态。对应 course_summary.status 字段。
 * 流转：PENDING → RUNNING → SUCCESS / FAILED。
 *
 * <p>总结参考「仅聊天记录」生成，一次要几十秒。接口不能挂着干等，
 * 所以先落一条 PENDING 立刻返回，前端轮询状态，好了再取正文。
 *
 * <p>与 {@link AiTaskStatus} 分开而不是复用：那个还带 {@code PARTIAL}
 * （逐页解析允许部分成功），总结是「要么整篇出来、要么失败」，
 * 语义不同，强行复用会让两边的状态机都变模糊。
 */
public enum SummaryStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}
