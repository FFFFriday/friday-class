package com.fridayclass.dto.admin;

import com.fridayclass.dto.ClassGroupRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端新建 / 修改班级请求。对应 {@code POST|PUT /api/admin/class-groups}。
 *
 * <h3>为什么另开一个 record，而不是给 {@link ClassGroupRequest} 加个字段</h3>
 *
 * 「换班主任」只有管理员能做，教师**不能**（2026-09-22 Friday 拍板：
 * 「管理员能修改，其他人均不能修改，包括老师」）。教师端与管理员端复用
 * {@link ClassGroupRequest}，一旦在那个类上加 {@code teacherId}，
 * 教师端接口就会开始接收它 —— 那就只能靠"传了再拒绝"来兜底，
 * 而校验总有人会忘。分成两个 record 之后，**教师端在类型层面就收不到
 * {@code teacherId}**：连传的入口都没有，不必依赖任何一处 if。
 *
 * <p>这与 {@code AdminCreateUserRequest} 不接受 {@code role}、
 * 注册接口不接受 {@code role} 是同一个道理 —— 见 {@link com.fridayclass.enums.Role} 的类注释。
 *
 * @param teacherId 班主任。**可空**：不传则归操作的管理员自己（沿用改造前的行为，
 *                  避免既有调用点突然 400）。传了必须是教师账号，否则 400。
 */
public record AdminClassGroupRequest(

        @NotBlank(message = "班级名不能为空")
        @Size(max = 100, message = "班级名最长 100 个字符")
        String name,

        @Size(max = 255, message = "备注最长 255 个字符")
        String description,

        Long teacherId
) {

    /** 落到 Service 里用的通用请求（Service 不认识管理端 DTO）。 */
    public ClassGroupRequest toBase() {
        return new ClassGroupRequest(name, description);
    }
}
