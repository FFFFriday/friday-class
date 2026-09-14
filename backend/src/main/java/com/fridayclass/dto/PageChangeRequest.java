package com.fridayclass.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 翻页请求。对应 POST /api/session/{id}/page。
 *
 * <p>上限用 {@code MAX_PAGES} 兜底，真实上限由 Service 按该课件的实际页数再校验一次
 * （课件页数只有查询后才知道，注解里写不死）。
 */
public record PageChangeRequest(

        @NotNull(message = "页码不能为空")
        @Min(value = 1, message = "页码从 1 开始")
        @Max(value = MAX_PAGES, message = "页码超出范围")
        Integer pageNo
) {
    /** 与 PptxConverter 的单课件页数上限保持一致。 */
    public static final int MAX_PAGES = 500;
}
