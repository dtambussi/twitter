package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.FollowValidationError;

import java.time.Instant;

public record Follow(
    UserId followerId,
    UserId followeeId,
    Instant createdAt
) {
    /**
     * Creates a Follow relationship, returning a Result for expected validation failures.
     */
    public static Result<Follow, FollowValidationError> create(UserId followerId, UserId followeeId) {
        if (followerId.equals(followeeId)) {
            return Result.failure(FollowValidationError.SelfFollow.INSTANCE);
        }
        return Result.success(new Follow(followerId, followeeId, Instant.now()));
    }
}
