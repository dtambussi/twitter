package com.twitter.application.service;

import com.twitter.application.port.out.FollowRepository;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.UserRepository;
import com.twitter.domain.error.FollowError;
import com.twitter.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FollowService.
 * Tests service logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowService")
class FollowServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MetricsPort metrics;

    private FollowService followService;

    @BeforeEach
    void setUp() {
        followService = new FollowService(
                followRepository, userRepository, outboxRepository, idGenerator, metrics
        );
    }

    @Nested
    @DisplayName("followUser")
    class FollowUserTests {

        @Test
        @DisplayName("Should create follow relationship")
        void shouldCreateFollowRelationship() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(false);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            var result = followService.followUser(follower, followee);

            // Then
            assertTrue(result.isSuccess());
            verify(followRepository).save(any());
            verify(userRepository).upsert(any());
            verify(outboxRepository).save(any(), any());
            verify(metrics).incrementFollows();
        }

        @Test
        @DisplayName("Should fail when following self")
        void shouldFailWhenFollowingSelf() {
            // Given
            UserId user = UserId.random();

            // When
            var result = followService.followUser(user, user);

            // Then
            assertTrue(result.isFailure());
            assertInstanceOf(FollowError.ValidationFailed.class, result.errorOrNull());
            verifyNoInteractions(followRepository);
        }

        @Test
        @DisplayName("Should fail when already following")
        void shouldFailWhenAlreadyFollowing() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(true);

            // When
            var result = followService.followUser(follower, followee);

            // Then
            assertTrue(result.isFailure());
            assertInstanceOf(FollowError.AlreadyFollowing.class, result.errorOrNull());
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should upsert followee user")
        void shouldUpsertFolloweeUser() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(false);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            followService.followUser(follower, followee);

            // Then
            verify(userRepository).upsert(argThat(user -> user.id().equals(followee)));
        }

        @Test
        @DisplayName("Should create UserFollowed event in outbox")
        void shouldCreateOutboxEvent() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(false);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            followService.followUser(follower, followee);

            // Then
            verify(outboxRepository).save(any(), any());
        }
    }

    @Nested
    @DisplayName("unfollow")
    class UnfollowTests {

        @Test
        @DisplayName("Should remove follow relationship")
        void shouldRemoveFollowRelationship() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(true);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            var result = followService.unfollow(follower, followee);

            // Then
            assertTrue(result.isSuccess());
            verify(followRepository).delete(follower, followee);
            verify(outboxRepository).save(any(), any());
            verify(metrics).incrementUnfollows();
        }

        @Test
        @DisplayName("Should fail when not following")
        void shouldFailWhenNotFollowing() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(false);

            // When
            var result = followService.unfollow(follower, followee);

            // Then
            assertTrue(result.isFailure());
            assertInstanceOf(FollowError.NotFollowing.class, result.errorOrNull());
            verify(followRepository, never()).delete(any(), any());
        }

        @Test
        @DisplayName("Should create UserUnfollowed event in outbox")
        void shouldCreateUnfollowOutboxEvent() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            when(followRepository.exists(follower, followee)).thenReturn(true);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            followService.unfollow(follower, followee);

            // Then
            verify(outboxRepository).save(any(), any());
        }
    }
}
