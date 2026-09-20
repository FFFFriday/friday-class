package com.fridayclass.dto;

import java.util.List;

/**
 * 某个学生在这节课上问过的问题（M5）。
 *
 * <p><b>没跟 AI 聊过的学生不会出现在列表里</b>——这是明确需求（「没有与 AI 聊天的则不显示」）。
 * 实现上很直接：分组是从问答记录出发做的，没有记录的学生自然构不出这一组。
 * 不需要特判，也不会出现「有学生但 records 为空」的空壳组。
 *
 * @param userId   学生 ID
 * @param nickname 昵称（为空时前端显示「同学」）
 * @param askCount 提问次数。等于 {@code records.size()}，单独给出来是让前端不必先算长度
 */
public record StudentQaGroup(
        Long userId,
        String nickname,
        int askCount,
        List<QaRecordResponse> records) {

    public static StudentQaGroup of(Long userId, String nickname, List<QaRecordResponse> records) {
        return new StudentQaGroup(userId, nickname, records.size(), records);
    }
}
