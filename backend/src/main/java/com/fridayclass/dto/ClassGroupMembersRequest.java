package com.fridayclass.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量加人请求。对应 POST /api/class-groups/{id}/members。
 *
 * <p>上限 200 是防误操作的护栏：这个接口一次性插入，传进来一万个 id
 * 会长时间占着数据库连接，而且多半是前端出了 bug 而不是真实需求。
 */
public record ClassGroupMembersRequest(

        @NotEmpty(message = "请至少选择一名学生")
        @Size(max = 200, message = "一次最多添加 200 人")
        List<Long> userIds
) {
}
