package com.fridayclass.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 踢人。
 *
 * @param userId 要移出课堂的用户
 * @param reason 给被踢者看的理由。<b>这个值会原样发到他屏幕上</b>，
 *               所以别在这里写内部信息（如管理员账号、内部编号）。
 */
public record KickRequest(
        @NotNull(message = "缺少 userId")
        Long userId,

        @Size(max = 100, message = "理由最长 100 个字符")
        String reason) {
}
