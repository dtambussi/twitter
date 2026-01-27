package com.twitter.domain.model;

import java.time.Instant;

public record User(
    UserId id,
    Instant createdAt
) {
    public static User create(UserId id) {
        return new User(id, Instant.now());
    }
}
