package com.twitter.application.port.out;

import com.twitter.domain.model.Follow;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;

import java.time.Instant;
import java.util.List;

public interface FollowRepository {
    void save(Follow follow);
    void delete(UserId followerId, UserId followeeId);
    boolean exists(UserId followerId, UserId followeeId);

    /**
     * Find users that this user follows, ordered by follow time (newest first).
     * Cursor is ISO timestamp of the last follow's created_at.
     */
    List<FollowedUser> findFollowing(UserId userId, String cursor, int limit);

    /**
     * Find users that follow this user, ordered by follow time (newest first).
     * Cursor is ISO timestamp of the last follow's created_at.
     */
    List<FollowedUser> findFollowers(UserId userId, String cursor, int limit);

    List<UserId> findAllFollowerIds(UserId userId);

    /**
     * User with the timestamp of when the follow relationship was created.
     */
    record FollowedUser(User user, Instant followedAt) {}

    /**
     * Counts the number of followers for a user.
     */
    long countFollowers(UserId userId);

    /**
     * Finds users that the given user follows who have more followers than the threshold.
     * Used for fan-out on read (celebrity tweets).
     */
    List<UserId> findFollowedCelebrities(UserId userId, int followerThreshold);

    long count();
    void deleteAll();
}
