package com.fridayclass.enums;

/**
 * 用户角色。对应 user.role 字段。
 *
 * <p>{@code ADMIN} <b>不进自助注册</b>。注册接口（{@code POST /api/auth/register}）
 * 写死只建学生、不接收 role 字段，这是有意的防提权设计——否则任何人都能
 * 注册成管理员来管全平台账号。管理员只能由 {@code db/seed_admin.sql} 预置。
 */
public enum Role {
    TEACHER,
    STUDENT,
    ADMIN
}
