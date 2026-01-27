package com.twitter.domain.model;

import java.util.List;

public record Page<T>(
    List<T> data,
    String nextCursor,
    boolean hasMore
) {
    public static <T> Page<T> of(List<T> data, String nextCursor) {
        return new Page<>(data, nextCursor, nextCursor != null);
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), null, false);
    }
}
