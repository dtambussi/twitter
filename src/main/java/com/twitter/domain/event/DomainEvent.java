package com.twitter.domain.event;

import java.time.Instant;
import java.util.UUID;

public sealed interface DomainEvent permits TweetCreated, UserFollowed, UserUnfollowed {
    UUID eventId();
    String aggregateId();
    Instant occurredAt();
    String eventType();
}
