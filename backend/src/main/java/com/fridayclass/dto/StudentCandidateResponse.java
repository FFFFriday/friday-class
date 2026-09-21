package com.fridayclass.dto;

import com.fridayclass.entity.User;

/**
 * 可加入班级的学生（搜人结果）。对应 GET /api/class-groups/{id}/candidates。
 *
 * <p>后端**已经排除**了班内已有的学生，前端不必自己去重 ——
 * 而且必须后端做：前端去重意味着未过滤的完整名单会先发到浏览器，
 * 一个「反正前端会滤掉」的接口，等于把全校学生名单开放给了任何教师。
 */
public record StudentCandidateResponse(
        Long id,
        String username,
        String nickname) {

    public static StudentCandidateResponse from(User user) {
        return new StudentCandidateResponse(user.getId(), user.getUsername(), user.getNickname());
    }
}
