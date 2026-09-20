package com.fridayclass.dto.admin;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 清理孤立文件的请求。
 *
 * <p><b>必须显式带上要删的路径列表</b>，没有「一键清空」的形式。
 * 这样调用方至少得先调一次 {@code GET /orphans}、把那个列表看过一遍——
 * 哪怕只是程序化地转发，也多了一次「确实落在了某份清单上」的机会。
 *
 * <p>服务端在真正删除前<b>还会重新校验一遍</b>这些路径是否仍是孤立文件。
 */
public record StorageCleanRequest(
        @NotEmpty(message = "没有指定要清理的路径")
        List<String> paths) {
}
