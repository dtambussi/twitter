package com.twitter.application.service;

import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.TweetRepository;
import com.twitter.domain.error.TweetError;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TweetService.
 * Tests service logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TweetService")
class TweetServiceTest {

    @Mock
    private TweetRepository tweetRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MetricsPort metrics;

    private TweetService tweetService;

    @BeforeEach
    void setUp() {
        tweetService = new TweetService(tweetRepository, outboxRepository, idGenerator, metrics);
    }

    @Nested
    @DisplayName("createTweet")
    class CreateTweetTests {

        @Test
        @DisplayName("Should create tweet with valid content")
        void shouldCreateTweetWithValidContent() {
            // Given
            UserId userId = UserId.random();
            String content = "Hello, World!";
            UUID tweetId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            when(idGenerator.generate()).thenReturn(tweetId, eventId);

            // When
            var result = tweetService.createTweet(userId, content);

            // Then
            assertTrue(result.isSuccess());
            Tweet tweet = result.getOrThrow();
            assertEquals(tweetId, tweet.id());
            assertEquals(userId, tweet.userId());
            assertEquals(content, tweet.content());

            verify(tweetRepository).save(any(Tweet.class));
            verify(outboxRepository).save(any(), any());
            verify(metrics).incrementTweetsCreated();
        }

        @Test
        @DisplayName("Should trim whitespace from content")
        void shouldTrimWhitespace() {
            // Given
            UserId userId = UserId.random();
            String content = "  Hello  ";
            UUID tweetId = UUID.randomUUID();

            when(idGenerator.generate()).thenReturn(tweetId, UUID.randomUUID());

            // When
            var result = tweetService.createTweet(userId, content);

            // Then
            assertTrue(result.isSuccess());
            assertEquals("Hello", result.getOrThrow().content());
        }

        @Test
        @DisplayName("Should fail with empty content")
        void shouldFailWithEmptyContent() {
            // Given
            UserId userId = UserId.random();
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            var result = tweetService.createTweet(userId, "");

            // Then
            assertTrue(result.isFailure());
            assertInstanceOf(TweetError.ValidationFailed.class, result.errorOrNull());
            verifyNoInteractions(tweetRepository);
            verifyNoInteractions(outboxRepository);
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("Should fail with content exceeding 280 characters")
        void shouldFailWithContentTooLong() {
            // Given
            UserId userId = UserId.random();
            String longContent = "x".repeat(281);
            when(idGenerator.generate()).thenReturn(UUID.randomUUID());

            // When
            var result = tweetService.createTweet(userId, longContent);

            // Then
            assertTrue(result.isFailure());
            assertInstanceOf(TweetError.ValidationFailed.class, result.errorOrNull());
            verifyNoInteractions(tweetRepository);
        }

        @Test
        @DisplayName("Should save tweet to repository")
        void shouldSaveTweetToRepository() {
            // Given
            UserId userId = UserId.random();
            String content = "Test tweet";
            UUID tweetId = UUID.randomUUID();

            when(idGenerator.generate()).thenReturn(tweetId, UUID.randomUUID());

            // When
            tweetService.createTweet(userId, content);

            // Then
            ArgumentCaptor<Tweet> captor = ArgumentCaptor.forClass(Tweet.class);
            verify(tweetRepository).save(captor.capture());

            Tweet savedTweet = captor.getValue();
            assertEquals(tweetId, savedTweet.id());
            assertEquals(userId, savedTweet.userId());
            assertEquals(content, savedTweet.content());
        }

        @Test
        @DisplayName("Should create outbox event")
        void shouldCreateOutboxEvent() {
            // Given
            UserId userId = UserId.random();
            String content = "Test tweet";
            UUID tweetId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            when(idGenerator.generate()).thenReturn(tweetId, eventId);

            // When
            tweetService.createTweet(userId, content);

            // Then
            verify(outboxRepository).save(any(), any());
        }
    }

    @Nested
    @DisplayName("getUserTweets")
    class GetUserTweetsTests {

        @Test
        @DisplayName("Should return user tweets")
        void shouldReturnUserTweets() {
            // Given
            UserId userId = UserId.random();
            List<Tweet> tweets = List.of(
                    new Tweet(UUID.randomUUID(), userId, "Tweet 1", Instant.now()),
                    new Tweet(UUID.randomUUID(), userId, "Tweet 2", Instant.now())
            );

            when(tweetRepository.findByUserId(userId, null, 11)).thenReturn(tweets);

            // When
            Page<Tweet> result = tweetService.getUserTweets(userId, null, 10);

            // Then
            assertEquals(2, result.data().size());
            assertNull(result.nextCursor());
        }

        @Test
        @DisplayName("Should paginate with cursor when more results exist")
        void shouldPaginateWithCursor() {
            // Given
            UserId userId = UserId.random();
            List<Tweet> tweets = List.of(
                    new Tweet(UUID.randomUUID(), userId, "Tweet 1", Instant.now()),
                    new Tweet(UUID.randomUUID(), userId, "Tweet 2", Instant.now()),
                    new Tweet(UUID.randomUUID(), userId, "Tweet 3", Instant.now()) // Extra for hasMore
            );

            when(tweetRepository.findByUserId(userId, null, 3)).thenReturn(tweets);

            // When
            Page<Tweet> result = tweetService.getUserTweets(userId, null, 2);

            // Then
            assertEquals(2, result.data().size());
            assertNotNull(result.nextCursor());
        }

        @Test
        @DisplayName("Should return empty page for user with no tweets")
        void shouldReturnEmptyPage() {
            // Given
            UserId userId = UserId.random();
            when(tweetRepository.findByUserId(userId, null, 11)).thenReturn(List.of());

            // When
            Page<Tweet> result = tweetService.getUserTweets(userId, null, 10);

            // Then
            assertTrue(result.data().isEmpty());
            assertNull(result.nextCursor());
        }
    }
}
