package com.twitter.domain.event;

import com.twitter.domain.model.UserId;

import java.time.Instant;
import java.util.UUID;

public record TweetCreated(
    UUID eventId,
    UUID tweetId,
    UserId userId,
    String content,
    Instant occurredAt
) implements DomainEvent {

    public static TweetCreated from(UUID eventId, UUID tweetId, UserId userId, String content) {
        return new TweetCreated(eventId, tweetId, userId, content, Instant.now());
    }

    // The User is the main 'box' (Aggregate Root) that this event belongs to
    @Override
    public String aggregateId() {
        return userId.toString();
    }

    @Override
    public String eventType() {
        return "TWEET_CREATED";
    }
}
