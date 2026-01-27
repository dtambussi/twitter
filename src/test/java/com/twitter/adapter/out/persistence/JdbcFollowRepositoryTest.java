package com.twitter.adapter.out.persistence;

import com.twitter.application.port.out.FollowRepository;
import com.twitter.domain.model.Follow;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIf("isDockerAvailable")
class JdbcFollowRepositoryTest extends FullStackTestBase {

    @Autowired
    private JdbcFollowRepository followRepository;

    @Autowired
    private JdbcUserRepository userRepository;

    private UserId alice;
    private UserId bob;
    private UserId charlie;

    @BeforeEach
    void setUpTestUsers() {
        // Create test users (database is cleaned by parent @BeforeEach)
        alice = UserId.random();
        bob = UserId.random();
        charlie = UserId.random();

        userRepository.upsert(User.create(alice));
        userRepository.upsert(User.create(bob));
        userRepository.upsert(User.create(charlie));
    }

    @Test
    void shouldSaveAndCheckFollowRelationship() {
        // Given
        Follow follow = Follow.create(alice, bob).getOrThrow();

        // When
        followRepository.save(follow);

        // Then
        assertTrue(followRepository.exists(alice, bob));
        assertFalse(followRepository.exists(bob, alice));
    }

    @Test
    void shouldNotCreateDuplicateFollows() {
        // Given
        Follow follow = Follow.create(alice, bob).getOrThrow();

        // When
        followRepository.save(follow);
        followRepository.save(follow); // Try to save again

        // Then - should only have one follow relationship
        assertTrue(followRepository.exists(alice, bob));
        assertEquals(1, followRepository.count());
    }

    @Test
    void shouldDeleteFollowRelationship() {
        // Given
        Follow follow = Follow.create(alice, bob).getOrThrow();
        followRepository.save(follow);
        assertTrue(followRepository.exists(alice, bob));

        // When
        followRepository.delete(alice, bob);

        // Then
        assertFalse(followRepository.exists(alice, bob));
    }

    @Test
    void shouldFindFollowing() {
        // Given
        followRepository.save(Follow.create(alice, bob).getOrThrow());
        followRepository.save(Follow.create(alice, charlie).getOrThrow());

        // When
        List<FollowRepository.FollowedUser> following = followRepository.findFollowing(alice, null, 10);

        // Then
        assertEquals(2, following.size());
        assertTrue(following.stream().anyMatch(fu -> fu.user().id().equals(bob)));
        assertTrue(following.stream().anyMatch(fu -> fu.user().id().equals(charlie)));
    }

    @Test
    void shouldFindFollowers() {
        // Given
        followRepository.save(Follow.create(alice, bob).getOrThrow());
        followRepository.save(Follow.create(charlie, bob).getOrThrow());

        // When
        List<FollowRepository.FollowedUser> followers = followRepository.findFollowers(bob, null, 10);

        // Then
        assertEquals(2, followers.size());
        assertTrue(followers.stream().anyMatch(fu -> fu.user().id().equals(alice)));
        assertTrue(followers.stream().anyMatch(fu -> fu.user().id().equals(charlie)));
    }

    @Test
    void shouldFindAllFollowerIds() {
        // Given
        followRepository.save(Follow.create(alice, bob).getOrThrow());
        followRepository.save(Follow.create(charlie, bob).getOrThrow());

        // When
        List<UserId> followerIds = followRepository.findAllFollowerIds(bob);

        // Then
        assertEquals(2, followerIds.size());
        assertTrue(followerIds.contains(alice));
        assertTrue(followerIds.contains(charlie));
    }

    @Test
    void shouldCountFollowers() {
        // Given
        followRepository.save(Follow.create(alice, bob).getOrThrow());
        followRepository.save(Follow.create(charlie, bob).getOrThrow());

        // When
        long count = followRepository.countFollowers(bob);

        // Then
        assertEquals(2, count);
    }

    @Test
    void shouldFindFollowedCelebrities() {
        // Given - bob has 2 followers, charlie has 3 followers
        followRepository.save(Follow.create(alice, bob).getOrThrow());
        followRepository.save(Follow.create(charlie, bob).getOrThrow());
        
        UserId david = UserId.random();
        userRepository.upsert(User.create(david));
        followRepository.save(Follow.create(alice, charlie).getOrThrow());
        followRepository.save(Follow.create(bob, charlie).getOrThrow());
        followRepository.save(Follow.create(david, charlie).getOrThrow());

        // alice follows both bob and charlie
        // charlie has 3 followers (threshold = 2)

        // When
        List<UserId> celebrities = followRepository.findFollowedCelebrities(alice, 2);

        // Then - charlie should be a celebrity (3 followers >= 2), bob should not (2 followers = 2, but we need >)
        assertEquals(1, celebrities.size());
        assertTrue(celebrities.contains(charlie));
    }
}
