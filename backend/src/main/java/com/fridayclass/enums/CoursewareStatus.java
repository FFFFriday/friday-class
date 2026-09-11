package com.fridayclass.enums;

/**
 * 课件处理状态。对应 courseware.status 字段。
 * 流转：UPLOADED → CONVERTING → CONVERTED → PARSING → PARSED（任一环节可 FAILED）。
 */
public enum CoursewareStatus {
    UPLOADED,
    CONVERTING,
    CONVERTED,
    PARSING,
    PARSED,
    FAILED
}
