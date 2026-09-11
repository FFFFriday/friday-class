package com.fridayclass.dto;

import java.util.List;

/**
 * 不分页的列表结果。对应契约里 {@code data: { list: [...] }} 这种形状
 * （如课件页列表）。
 */
public record ListResult<T>(List<T> list) {

    public static <T> ListResult<T> of(List<T> list) {
        return new ListResult<>(list);
    }
}
