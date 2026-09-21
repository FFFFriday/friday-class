package com.fridayclass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改班级请求。对应 POST /api/class-groups、PUT /api/class-groups/{id}。
 *
 * <p>教师不在这里传：建班时取当前登录用户，**不给前端指定 {@code teacherId} 的机会** ——
 * 否则教师 A 能伪造成教师 B 的班。这与注册接口不接收 {@code role} 是同一个道理。
 */
public record ClassGroupRequest(

        @NotBlank(message = "班级名不能为空")
        @Size(max = 100, message = "班级名最长 100 个字符")
        String name,

        @Size(max = 255, message = "备注最长 255 个字符")
        String description
) {
}
