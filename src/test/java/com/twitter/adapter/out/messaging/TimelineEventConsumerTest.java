package com.twitter.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twitter.application.port.out.FollowQueryPort;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.application.port.out.TweetQueryPort;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.sharding.ShardRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TimelineEventConsumer.
 * Tests event handling logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineEventConsumer")
class TimelineEventConsumerTest {

    @Mock
    private TimelineRepository timelineRepository;

    @Mock
    private FollowQueryPort followQueryPort;

    @Mock
    private TweetQueryPort tweetQueryPort;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MetricsPort metrics;

    @Mock
    private ShardRouter shardRouter;

    private ObjectMapper objectMapper;
    private AppProperties appProperties;
    private TimelineEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        appProperties = new AppProperties();
        appProperties.setTimeline(new AppProperties.Timeline());
        appProperties.getTimeline().setCelebrityFollowerThreshold(10000);
        appProperties.getTimeline().setMaxSize(800);

        consumer = new TimelineEventConsumer(
                timelineRepository, followQueryPort, tweetQueryPort,
                idGenerator, objectMapper, metrics, appProperties, shardRouter
        );
    }

    @Nested
    @DisplayName("TWEET_CREATED event")
    class TweetCreatedTests {

        @Test
        @DisplayName("Should fan-out tweet to followers")
        void shouldFanOutTweetToFollowers() {
            // Given
            UserId author = UserId.random();
            UUID tweetId = UUID.randomUUID();
            UserId follower1 = UserId.random();
            UserId follower2 = UserId.random();

            String payload = String.format(
                    "{\"tweetId\":\"%s\",\"userId\":{\"value\":\"%s\"},\"content\":\"Test\"}",
                    tweetId, author
            );

            ConsumerRecord<String, String> record = createRecord("TWEET_CREATED", author.toString(), payload);

            when(followQueryPort.countFollowers(author)).thenReturn(100L); // Not a celebrity
            when(followQueryPort.findAllFollowerIds(author)).thenReturn(List.of(follower1, follower2));
            when(idGenerator.extractTimestamp(tweetId)).thenReturn(123456789L);
            doAnswer(inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
            }).when(metrics).recordFanoutDuration(any(Runnable.class));

            // When
            consumer.consume(record);

            // Then
            verify(timelineRepository).addTweet(follower1, tweetId, 123456789L);
            verify(timelineRepository).addTweet(follower2, tweetId, 123456789L);
        }

        @Test
        @DisplayName("Should skip fan-out for celebrity users")
        void shouldSkipFanOutForCelebrity() {
            // Given
            UserId celebrity = UserId.random();
            UUID tweetId = UUID.randomUUID();

            String payload = String.format(
                    "{\"tweetId\":\"%s\",\"userId\":{\"value\":\"%s\"},\"content\":\"Test\"}",
                    tweetId, celebrity
            );

            ConsumerRecord<String, String> record = createRecord("TWEET_CREATED", celebrity.toString(), payload);

            when(followQueryPort.countFollowers(celebrity)).thenReturn(50000L); // Celebrity
            when(idGenerator.extractTimestamp(tweetId)).thenReturn(123456789L);

            // When
            consumer.consume(record);

            // Then
            verify(followQueryPort, never()).findAllFollowerIds(any());
            verify(timelineRepository, never()).addTweet(any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("USER_FOLLOWED event")
    class UserFollowedTests {

        @Test
        @DisplayName("Should backfill tweets to follower timeline")
        void shouldBackfillTweets() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();
            UUID tweetId = UUID.randomUUID();

            Tweet tweet = new Tweet(tweetId, followee, "Test tweet", Instant.now());

            String payload = String.format(
                    "{\"followerId\":{\"value\":\"%s\"},\"followeeId\":{\"value\":\"%s\"}}",
                    follower, followee
            );

            ConsumerRecord<String, String> record = createRecord("USER_FOLLOWED", follower.toString(), payload);

            when(tweetQueryPort.findByUserIdLatest(followee, 800)).thenReturn(List.of(tweet));
            when(idGenerator.extractTimestamp(tweetId)).thenReturn(123456789L);

            // When
            consumer.consume(record);

            // Then
            verify(timelineRepository).addTweets(eq(follower), anyList());
        }

        @Test
        @DisplayName("Should handle followee with no tweets")
        void shouldHandleFolloweeWithNoTweets() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();

            String payload = String.format(
                    "{\"followerId\":{\"value\":\"%s\"},\"followeeId\":{\"value\":\"%s\"}}",
                    follower, followee
            );

            ConsumerRecord<String, String> record = createRecord("USER_FOLLOWED", follower.toString(), payload);

            when(tweetQueryPort.findByUserIdLatest(followee, 800)).thenReturn(List.of());

            // When
            consumer.consume(record);

            // Then
            verify(timelineRepository, never()).addTweets(any(), anyList());
        }
    }

    @Nested
    @DisplayName("USER_UNFOLLOWED event")
    class UserUnfollowedTests {

        @Test
        @DisplayName("Should remove tweets from follower timeline")
        void shouldRemoveTweetsFromTimeline() {
            // Given
            UserId follower = UserId.random();
            UserId followee = UserId.random();
            UUID tweetId = UUID.randomUUID();

            Tweet tweet = new Tweet(tweetId, followee, "Test tweet", Instant.now());

            String payload = String.format(
                    "{\"followerId\":{\"value\":\"%s\"},\"followeeId\":{\"value\":\"%s\"}}",
                    follower, followee
            );

            ConsumerRecord<String, String> record = createRecord("USER_UNFOLLOWED", follower.toString(), payload);

            when(tweetQueryPort.findByUserIdLatest(followee, 800)).thenReturn(List.of(tweet));

            // When
            consumer.consume(record);

            // Then
            verify(timelineRepository).removeTweetsByUser(eq(follower), eq(followee), eq(List.of(tweetId)));
        }
    }

    @Nested
    @DisplayName("Unknown events")
    class UnknownEventTests {

        @Test
        @DisplayName("Should handle unknown event type gracefully")
        void shouldHandleUnknownEventType() {
            // Given
            ConsumerRecord<String, String> record = createRecord("UNKNOWN_EVENT", UUID.randomUUID().toString(), "{}");

            // When - should not throw
            consumer.consume(record);

            // Then - no interactions with repositories
            verifyNoInteractions(timelineRepository);
        }
    }

    private ConsumerRecord<String, String> createRecord(String eventType, String key, String payload) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("eventType", eventType.getBytes());

        return new ConsumerRecord<>(
                "twitter.events",                              // topic
                0,                                             // partition
                0L,                                            // offset
                ConsumerRecord.NO_TIMESTAMP,                   // timestamp
                org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                0,                                             // serializedKeySize
                0,                                             // serializedValueSize
                key,                                           // key
                payload,                                       // value
                headers,                                       // headers
                java.util.Optional.empty()                     // leaderEpoch
        );
    }
}
