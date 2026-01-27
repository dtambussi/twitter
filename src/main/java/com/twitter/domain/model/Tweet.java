package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.TweetError;

import java.time.Instant;
import java.util.UUID;

public record Tweet(
    UUID id,
    UserId userId,
    String content,
    Instant createdAt
) {
    public static final int MAX_CONTENT_LENGTH = 280;

    /**
     * Creates a Tweet, returning a Result for expected validation failures.
     */
    public static Result<Tweet, TweetError> create(UUID id, UserId userId, String content) {
        if (content == null || content.isBlank()) {
            return Result.failure(TweetError.EmptyContent.INSTANCE);
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            return Result.failure(new TweetError.ContentTooLong(trimmed.length(), MAX_CONTENT_LENGTH));
        }
        return Result.success(new Tweet(id, userId, trimmed, Instant.now()));
    }
}
