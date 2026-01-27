package com.twitter.application.service;

import com.twitter.application.port.in.FollowUserUseCase;
import com.twitter.application.port.in.GetFollowersUseCase;
import com.twitter.application.port.in.GetFollowingUseCase;
import com.twitter.application.port.in.UnfollowUserUseCase;
import com.twitter.application.port.out.FollowRepository;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.UserRepository;
import com.twitter.domain.error.FollowError;
import com.twitter.domain.event.UserFollowed;
import com.twitter.domain.event.UserUnfollowed;
import com.twitter.domain.model.Follow;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FollowService implements FollowUserUseCase, UnfollowUserUseCase, GetFollowingUseCase, GetFollowersUseCase {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final IdGenerator idGenerator;
    private final MetricsPort metrics;

    public FollowService(
            FollowRepository followRepository,
            UserRepository userRepository,
            OutboxRepository outboxRepository,
            IdGenerator idGenerator,
            MetricsPort metrics) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public Result<Void, FollowError> followUser(UserId followerId, UserId followeeId) {
        log.debug("Processing follow request: follower={}, followee={}", followerId, followeeId);
        
        // Domain validation via Follow.create()
        var followResult = Follow.create(followerId, followeeId);
        if (followResult.isFailure()) {
            log.warn("Follow validation failed: {}", followResult.errorOrNull().message());
            return Result.failure(new FollowError.ValidationFailed(followResult.errorOrNull()));
        }

        // Application-level validation (requires repository lookup)
        if (followRepository.exists(followerId, followeeId)) {
            log.debug("Already following: follower={}, followee={}", followerId, followeeId);
            return Result.failure(new FollowError.AlreadyFollowing(followerId, followeeId));
        }

        // Upsert target user (they may not exist yet)
        userRepository.upsert(User.create(followeeId));
        followRepository.save(followResult.getOrThrow());
        log.debug("Follow relationship saved");

        UserFollowed event = UserFollowed.from(
            idGenerator.generate(),
            followerId,
            followeeId
        );
        outboxRepository.save(event, RequestContext.getRequestId());
        log.debug("UserFollowed event queued in outbox");

        metrics.incrementFollows();
        log.info("Follow completed: {} -> {}", followerId, followeeId);

        return Result.success(null);
    }

    @Override
    @Transactional
    public Result<Void, FollowError> unfollow(UserId followerId, UserId followeeId) {
        log.debug("Processing unfollow request: follower={}, followee={}", followerId, followeeId);
        
        if (!followRepository.exists(followerId, followeeId)) {
            log.debug("Not following: follower={}, followee={}", followerId, followeeId);
            return Result.failure(new FollowError.NotFollowing(followerId, followeeId));
        }

        followRepository.delete(followerId, followeeId);
        log.debug("Follow relationship deleted");

        UserUnfollowed event = UserUnfollowed.from(
            idGenerator.generate(),
            followerId,
            followeeId
        );
        outboxRepository.save(event, RequestContext.getRequestId());
        log.debug("UserUnfollowed event queued in outbox");

        metrics.incrementUnfollows();
        log.info("Unfollow completed: {} -> {}", followerId, followeeId);

        return Result.success(null);
    }

    @Override
    public Page<User> getFollowing(UserId userId, String cursor, int limit) {
        var following = followRepository.findFollowing(userId, cursor, limit + 1);

        boolean hasMore = following.size() > limit;
        if (hasMore) {
            following = following.subList(0, limit);
        }

        String nextCursor = hasMore
            ? following.getLast().followedAt().toString()
            : null;

        List<User> users = following.stream()
            .map(FollowRepository.FollowedUser::user)
            .toList();

        return Page.of(users, nextCursor);
    }

    @Override
    public Page<User> getFollowers(UserId userId, String cursor, int limit) {
        var followers = followRepository.findFollowers(userId, cursor, limit + 1);

        boolean hasMore = followers.size() > limit;
        if (hasMore) {
            followers = followers.subList(0, limit);
        }

        String nextCursor = hasMore
            ? followers.getLast().followedAt().toString()
            : null;

        List<User> users = followers.stream()
            .map(FollowRepository.FollowedUser::user)
            .toList();

        return Page.of(users, nextCursor);
    }
}
