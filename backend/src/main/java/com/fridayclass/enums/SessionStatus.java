package com.fridayclass.enums;

/**
 * 课堂/直播会话状态。对应 class_session.status 字段。
 * 流转：NOT_STARTED → LIVE → ENDED。
 */
public enum SessionStatus {
    NOT_STARTED,
    LIVE,
    ENDED
}
