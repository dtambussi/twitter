package com.twitter.domain.event;

import com.twitter.domain.model.UserId;

import java.time.Instant;
import java.util.UUID;

public record UserFollowed(
    UUID eventId,
    UserId followerId,
    UserId followeeId,
    Instant occurredAt
) implements DomainEvent {

    public static UserFollowed from(UUID eventId, UserId followerId, UserId followeeId) {
        return new UserFollowed(eventId, followerId, followeeId, Instant.now());
    }

    @Override
    public String aggregateId() {
        return followerId.toString();
    }

    @Override
    public String eventType() {
        return "USER_FOLLOWED";
    }
}
