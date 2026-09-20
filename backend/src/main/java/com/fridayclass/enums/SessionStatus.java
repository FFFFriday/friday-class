package com.fridayclass.enums;

/**
 * 课堂/直播会话状态。对应 class_session.status 字段。
 * 流转：NOT_STARTED → LIVE ⇄ PAUSED → ENDED。
 *
 * <p>{@code PAUSED} 由管理端触发（「暂停所有课堂」）。要留意它的边界：
 * 媒体流是学生与老师<b>点对点直连</b>的，服务端管不到画面。暂停的实际效果是
 * 状态置为 PAUSED + 广播事件 + 学生端盖遮罩 + 禁用讨论区与提问，
 * <b>画面本身要靠老师端收到通知后自行停止共享</b>。
 * 这是 P2P 架构的固有边界，不是缺陷 —— 界面上必须写明，免得被当成 bug。
 */
public enum SessionStatus {
    NOT_STARTED,
    LIVE,
    PAUSED,
    ENDED
}
