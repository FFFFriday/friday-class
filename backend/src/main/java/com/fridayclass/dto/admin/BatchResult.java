package com.fridayclass.dto.admin;

import java.util.List;

/**
 * 批量操作的结果。
 *
 * <p><b>刻意不做「一个失败全部回滚」。</b>批量暂停 10 个课堂，其中一个因为
 * 已经被别处结课而失败——把另外 9 个也回滚掉是更糟的结果：管理员看到的是
 * 「点了没反应」，而他根本不知道哪几个成功了。
 *
 * <p>所以逐个执行、逐个记账，把成败与原因如实报回来。
 *
 * @param total     一共尝试了几个
 * @param succeeded 成功几个
 * @param failed    失败几个
 * @param failures  失败明细（成功的那些不列出来，避免刷屏）
 */
public record BatchResult(
        int total,
        int succeeded,
        int failed,
        List<Failure> failures) {

    public static BatchResult of(int total, int succeeded, List<Failure> failures) {
        return new BatchResult(total, succeeded, failures.size(), failures);
    }

    /**
     * @param id     课堂 ID
     * @param title  课堂名称（失败时管理员得知道是哪一节）
     * @param reason 失败原因（取后端给的那句人话，不是异常类名）
     */
    public record Failure(Long id, String title, String reason) {
    }
}
