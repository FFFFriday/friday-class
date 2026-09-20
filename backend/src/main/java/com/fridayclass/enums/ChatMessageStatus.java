package com.fridayclass.enums;

/**
 * 讨论区发言状态。对应 chat_message.status 字段。
 *
 * <p>用软删除而不是物理删除，理由有两条：
 * 一是课堂记录里「这里删过东西」本身是信息，直接抹掉会让记录对不上；
 * 二是学生把发言删了之后仍能追溯，避免「发了又删」规避事后追责。
 */
public enum ChatMessageStatus {
    NORMAL,
    DELETED
}
