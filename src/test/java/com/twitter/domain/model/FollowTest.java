package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.FollowValidationError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FollowTest {

    @Test
    void shouldCreateFollow() {
        UserId follower = UserId.random();
        UserId followee = UserId.random();

        var result = Follow.create(follower, followee);

        assertTrue(result.isSuccess());
        Follow follow = result.getOrThrow();
        assertEquals(follower, follow.followerId());
        assertEquals(followee, follow.followeeId());
        assertNotNull(follow.createdAt());
    }

    @Test
    void shouldFailWithSelfFollow() {
        UserId user = UserId.random();
        var result = Follow.create(user, user);

        assertTrue(result.isFailure());
        assertInstanceOf(FollowValidationError.SelfFollow.class, result.errorOrNull());
    }
}
