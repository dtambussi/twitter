package com.twitter.adapter.out.query;

import com.twitter.application.port.out.FollowQueryPort;
import com.twitter.application.port.out.FollowRepository;
import com.twitter.domain.model.UserId;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-process adapter for cross-module follow queries.
 * In a modular monolith, this delegates to FollowRepository.
 * In microservices, this would be replaced with an HTTP/gRPC client.
 */
@Component
public class FollowQueryAdapter implements FollowQueryPort {

    private final FollowRepository followRepository;

    public FollowQueryAdapter(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    @Override
    public List<UserId> findAllFollowerIds(UserId userId) {
        return followRepository.findAllFollowerIds(userId);
    }

    @Override
    public long countFollowers(UserId userId) {
        return followRepository.countFollowers(userId);
    }

    @Override
    public List<UserId> findFollowedCelebrities(UserId userId, int followerThreshold) {
        return followRepository.findFollowedCelebrities(userId, followerThreshold);
    }
}
