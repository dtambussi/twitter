package com.twitter.application.port.out;

import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module query port for accessing tweet data.
 * Used by modules that need to read tweets but don't own the Tweet domain.
 *
 * In a modular monolith: implemented by an adapter that calls TweetRepository.
 * In microservices: implemented by an HTTP/gRPC client to Tweet service.
 */
public interface TweetQueryPort {

    /**
     * Finds tweets by their IDs.
     */
    List<Tweet> findByIds(List<UUID> ids);

    /**
     * Finds the latest tweets by a user.
     */
    List<Tweet> findByUserIdLatest(UserId userId, int limit);
}
