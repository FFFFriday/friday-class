package com.fridayclass.enums;

/**
 * 课堂可见性。对应 {@code class_session.visibility} 字段。
 *
 * <p><b>这是「谁能看到这节课」的唯一开关</b>，与 {@code session_audience}（点名表）配合：
 * 判定口径见 {@code SessionAccessService} —— 一行 SQL 说清楚，不散落在各处。
 *
 * <p>{@code RESTRICTED} 是<b>数据库默认值也是 Java 字段默认值</b>（拒绝优先）。
 * 这个方向是刻意的：忘了设，结果是「谁也看不到」这个**显眼的 bug**，
 * 而不是「所有人都能看到」这个**沉默的泄漏**。与项目既有的安全立场一致
 * （参照 {@code SecurityConfig} 的「白名单之外一律拒绝」）。
 */
public enum SessionVisibility {
    /** 所有人可见。历史课堂（V3 迁移前就有的一批）一律回填成这个值。 */
    PUBLIC,

    /** 仅 {@code session_audience} 名单内的人可见。新建课堂的默认值。 */
    RESTRICTED
}
