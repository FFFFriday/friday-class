package com.fridayclass.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建一个文件夹。
 *
 * <p>只有 {@code name} 一个字段 —— <b>没有路径</b>。
 * 老师给的是「一个名字」，目录由服务端拼到他自己那个根下面。
 * 这一条本身就挡住了穿越：{@code ..}、{@code C:\}、{@code /etc/}
 * 都不是「一个名字」。
 *
 * <p>真正的校验在 {@code WorkspacePathSandbox}（七条规则），
 * 这里的 {@code @Size} 只是让常见错误在进业务层之前就有一句清楚的中文提示。
 */
public record AgentFolderRequest(

        @NotBlank(message = "文件夹名不能为空")
        @Size(max = 100, message = "文件夹名最长 100 个字符")
        String name) {
}
