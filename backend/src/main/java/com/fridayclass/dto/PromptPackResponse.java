package com.fridayclass.dto;

import java.util.List;

/**
 * 提示词包。对应 API 接口文档 §6.4。
 *
 * <p>学生进课堂时<b>一次拉全</b>该课件所有页的知识点与预置提问，
 * 之后翻页与提问都不用再查库——这是「按页码定位上下文」能成立的数据前提。
 *
 * @param coursewareId 课件 ID
 * @param parseVersion 解析版本号：该课件最近一次<b>成功</b>解析任务的 ID。
 *                     客户端必须缓存它，发现变大就丢弃旧缓存重拉——
 *                     否则会出现「进课堂时解析还没跑完、缓存下来是空的、于是永远为空」。
 *                     从未成功解析过时为 {@code 0}（任务 ID 自增且从 1 开始，所以 0 一定更小）。
 * @param parseStatus  课件当前状态。用来区分「还在解析」与「本页确实没内容」——
 *                     少了它前端只能一律提示「本页暂无内容」，会在解析中误导学生。
 * @param pages        全部页，按 pageNo 升序
 */
public record PromptPackResponse(
        Long coursewareId,
        long parseVersion,
        String parseStatus,
        List<PagePack> pages) {

    /**
     * 单页的提示词素材。
     *
     * <p>🔴 <b>{@code pageId} 是缓存键，绝不能用 {@code pageNo} 代替</b>：
     * 两份课件的「第 1 页」是两个不同的 pageId，用 pageNo 做键会互相覆盖，
     * 导致引用到<b>错误页</b>的知识点。这属于静默错误，极难排查。
     *
     * <p>{@code knowledgePoints} / {@code presetQuestions} 在本页没有内容时是
     * <b>空数组 {@code []}，不是 null</b>——前端可以直接遍历，不必判空。
     */
    public record PagePack(
            Long pageId,
            Integer pageNo,
            List<String> knowledgePoints,
            List<String> presetQuestions) {
    }
}
