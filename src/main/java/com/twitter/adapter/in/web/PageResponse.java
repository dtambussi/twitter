package com.twitter.adapter.in.web;

import com.twitter.domain.model.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
    List<T> data,
    Pagination pagination
) {
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        if (page == null) {
            return new PageResponse<>(List.of(), new Pagination(null, false));
        }
        List<T> data = page.data().stream().map(mapper).toList();
        Pagination pagination = new Pagination(page.nextCursor(), page.hasMore());
        return new PageResponse<>(data, pagination);
    }

    public record Pagination(
        String nextCursor,
        boolean hasMore
    ) {}
}
