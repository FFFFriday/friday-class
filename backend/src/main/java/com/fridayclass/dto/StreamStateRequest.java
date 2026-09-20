package com.fridayclass.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 开始 / 停止屏幕共享的请求体。
 *
 * <p>用 {@code Boolean} 而不是 {@code boolean}：基本类型缺省是 {@code false}，
 * 客户端漏传字段就会被当成「停止共享」，一声不响把全班画面关掉。
 * 用包装类型 + {@link NotNull} 能把这个错误变成一条明确的 400。
 */
public record StreamStateRequest(@NotNull(message = "live 不能为空") Boolean live) {
}
