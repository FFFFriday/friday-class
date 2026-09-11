package com.fridayclass.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页结果。对应 docs/api-contract.md 的 {@code { list, total, page, size }}。
 *
 * <p>注意页码口径：Spring Data 的 page 从 0 开始，本契约对外从 1 开始，
 * 转换集中在这里做，避免各处各写一遍。
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    public static <E, T> PageResult<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResult<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                page.getNumber() + 1,
                page.getSize());
    }
}
