package com.twitter.application.port.out;

import com.twitter.domain.model.UserId;

import java.util.List;

/**
 * Cross-module query port for accessing follow relationship data.
 * Used by modules that need to read follows but don't own the Follow domain.
 *
 * In a modular monolith: implemented by an adapter that calls FollowRepository.
 * In microservices: implemented by an HTTP/gRPC client to Follow service.
 */
public interface FollowQueryPort {

    /**
     * Finds all follower IDs for a user.
     */
    List<UserId> findAllFollowerIds(UserId userId);

    /**
     * Counts the number of followers for a user.
     */
    long countFollowers(UserId userId);

    /**
     * Finds celebrities (users with followers >= threshold) that a user follows.
     */
    List<UserId> findFollowedCelebrities(UserId userId, int followerThreshold);
}
