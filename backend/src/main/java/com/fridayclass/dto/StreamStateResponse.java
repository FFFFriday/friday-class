package com.fridayclass.dto;

import java.time.LocalDateTime;

/**
 * 当前课堂的共享流状态。
 *
 * <p>{@code startedAt} 仅在 {@code live = true} 时有值。
 * 它只是个展示用的时间戳——媒体流本身是老师与学生点对点直连的，
 * 服务端不经手画面，这里记录的是「老师说他开始共享了」这个事实。
 *
 * <p><b>{@code teacherId} 是必需的，不是附赠信息。</b>
 * WebRTC 信令要求按 {@code toUserId} 定向投递，而学生回 answer / ICE 时
 * <b>必须知道发给谁</b>——课堂上可能有多个老师（本项目支持多教师开课）。
 * 没有这个字段，学生就只能瞎猜或硬编码，协商根本建立不起来。
 */
public record StreamStateResponse(boolean live, LocalDateTime startedAt, Long teacherId) {

    public static StreamStateResponse of(boolean live, LocalDateTime startedAt, Long teacherId) {
        return new StreamStateResponse(live, startedAt, teacherId);
    }

    public static StreamStateResponse idle() {
        return new StreamStateResponse(false, null, null);
    }
}
