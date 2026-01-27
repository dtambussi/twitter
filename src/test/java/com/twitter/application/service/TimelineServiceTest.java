package com.twitter.application.service;

import com.twitter.application.port.out.FollowQueryPort;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.application.port.out.TweetQueryPort;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TimelineService.
 * Tests timeline logic including fan-out on read for celebrities.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineService")
class TimelineServiceTest {

    @Mock
    private TimelineRepository timelineRepository;

    @Mock
    private TweetQueryPort tweetQueryPort;

    @Mock
    private FollowQueryPort followQueryPort;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MetricsPort metrics;

    private AppProperties appProperties;
    private TimelineService timelineService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setTimeline(new AppProperties.Timeline());
        appProperties.getTimeline().setCelebrityFollowerThreshold(10000);

        timelineService = new TimelineService(
                timelineRepository, tweetQueryPort, followQueryPort,
                idGenerator, appProperties, metrics
        );
    }

    @Nested
    @DisplayName("getTimeline")
    class GetTimelineTests {

        @Test
        @DisplayName("Should return empty page when no tweets")
        void shouldReturnEmptyPageWhenNoTweets() {
            // Given
            UserId userId = UserId.random();
            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(List.of());
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of());

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 10);

            // Then
            assertTrue(result.data().isEmpty());
            assertNull(result.nextCursor());
            verify(metrics).incrementTimelineRequests();
        }

        @Test
        @DisplayName("Should return tweets from cache (fan-out on write)")
        void shouldReturnTweetsFromCache() {
            // Given
            UserId userId = UserId.random();
            UUID tweetId1 = UUID.randomUUID();
            UUID tweetId2 = UUID.randomUUID();

            List<UUID> cachedIds = List.of(tweetId1, tweetId2);
            List<Tweet> tweets = List.of(
                    new Tweet(tweetId1, UserId.random(), "Tweet 1", Instant.now()),
                    new Tweet(tweetId2, UserId.random(), "Tweet 2", Instant.now())
            );

            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(cachedIds);
            when(tweetQueryPort.findByIds(cachedIds)).thenReturn(tweets);
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of());

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 10);

            // Then
            assertEquals(2, result.data().size());
            verify(timelineRepository).getTimeline(userId, null, 11);
            verify(tweetQueryPort).findByIds(cachedIds);
        }

        @Test
        @DisplayName("Should fetch celebrity tweets (fan-out on read)")
        void shouldFetchCelebrityTweets() {
            // Given
            UserId userId = UserId.random();
            UserId celebrity = UserId.random();
            UUID celebrityTweetId = UUID.randomUUID();

            Tweet celebrityTweet = new Tweet(celebrityTweetId, celebrity, "Celebrity tweet", Instant.now());

            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(List.of());
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of(celebrity));
            when(tweetQueryPort.findByUserIdLatest(celebrity, 10)).thenReturn(List.of(celebrityTweet));

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 10);

            // Then
            assertEquals(1, result.data().size());
            assertEquals(celebrityTweetId, result.data().getFirst().id());
            verify(followQueryPort).findFollowedCelebrities(userId, 10000);
            verify(tweetQueryPort).findByUserIdLatest(celebrity, 10);
        }

        @Test
        @DisplayName("Should merge cached and celebrity tweets")
        void shouldMergeCachedAndCelebrityTweets() {
            // Given
            UserId userId = UserId.random();
            UserId celebrity = UserId.random();

            UUID cachedTweetId = UUID.randomUUID();
            UUID celebrityTweetId = UUID.randomUUID();

            Tweet cachedTweet = new Tweet(cachedTweetId, UserId.random(), "Cached tweet", Instant.now());
            Tweet celebrityTweet = new Tweet(celebrityTweetId, celebrity, "Celebrity tweet", Instant.now());

            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(List.of(cachedTweetId));
            when(tweetQueryPort.findByIds(List.of(cachedTweetId))).thenReturn(List.of(cachedTweet));
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of(celebrity));
            when(tweetQueryPort.findByUserIdLatest(celebrity, 10)).thenReturn(List.of(celebrityTweet));

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 10);

            // Then
            assertEquals(2, result.data().size());
        }

        @Test
        @DisplayName("Should paginate with cursor when more results exist")
        void shouldPaginateWithCursor() {
            // Given
            UserId userId = UserId.random();
            List<UUID> tweetIds = List.of(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()  // Extra for hasMore check
            );

            List<Tweet> tweets = tweetIds.stream()
                    .map(id -> new Tweet(id, UserId.random(), "Tweet", Instant.now()))
                    .toList();

            when(timelineRepository.getTimeline(userId, null, 3)).thenReturn(tweetIds);
            when(tweetQueryPort.findByIds(tweetIds)).thenReturn(tweets);
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of());

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 2);

            // Then
            assertEquals(2, result.data().size());
            assertNotNull(result.nextCursor());
        }

        @Test
        @DisplayName("Should deduplicate tweets")
        void shouldDeduplicateTweets() {
            // Given
            UserId userId = UserId.random();
            UserId celebrity = UserId.random();
            UUID sharedTweetId = UUID.randomUUID();

            Tweet sharedTweet = new Tweet(sharedTweetId, celebrity, "Shared tweet", Instant.now());

            // Same tweet appears in both cache and celebrity tweets
            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(List.of(sharedTweetId));
            when(tweetQueryPort.findByIds(List.of(sharedTweetId))).thenReturn(List.of(sharedTweet));
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of(celebrity));
            when(tweetQueryPort.findByUserIdLatest(celebrity, 10)).thenReturn(List.of(sharedTweet));

            // When
            Page<Tweet> result = timelineService.getTimeline(userId, null, 10);

            // Then
            assertEquals(1, result.data().size()); // Deduplicated
        }

        @Test
        @DisplayName("Should increment metrics on timeline request")
        void shouldIncrementMetrics() {
            // Given
            UserId userId = UserId.random();
            when(timelineRepository.getTimeline(userId, null, 11)).thenReturn(List.of());
            when(followQueryPort.findFollowedCelebrities(userId, 10000)).thenReturn(List.of());

            // When
            timelineService.getTimeline(userId, null, 10);

            // Then
            verify(metrics).incrementTimelineRequests();
        }
    }
}
