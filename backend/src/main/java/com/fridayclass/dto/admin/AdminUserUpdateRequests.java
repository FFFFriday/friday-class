package com.fridayclass.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端三个「改一个字段」的请求体。
 *
 * <p>放在同一个文件里而不是各建一个：它们都只有一到两个字段，
 * 分成三个文件只会在目录里多出三个几乎一样的形状，找起来更费劲。
 * （Java 允许多个顶层类型同文件，但只能有一个 public——所以这里用嵌套类型暴露。）
 */
public final class AdminUserUpdateRequests {

    private AdminUserUpdateRequests() {
    }

    /** 禁用 / 启用。{@code true} = 禁止登录。 */
    public record StatusRequest(@NotNull(message = "disabled 不能为空") Boolean disabled) {
    }

    /** 重置密码。 */
    public record PasswordRequest(
            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
            String newPassword) {
    }

    /** 改角色。 */
    public record RoleRequest(@NotBlank(message = "角色不能为空") String role) {
    }
}
