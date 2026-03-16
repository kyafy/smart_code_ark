package com.smartark.gateway.common.response;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size
) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
