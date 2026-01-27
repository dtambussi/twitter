package com.twitter.adapter.out.cache;

import com.twitter.application.port.out.TimelineRepository;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIf("isDockerAvailable")
class RedisTimelineRepositoryTest extends FullStackTestBase {

    @Autowired
    private RedisTimelineRepository repository;

    @Autowired
    private AppProperties appProperties;

    private UserId userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userId = UserId.random();
    }

    @Test
    void shouldAddAndRetrieveTweet() {
        // Given
        UUID tweetId = UUID.randomUUID();
        long score = System.currentTimeMillis();

        // When
        repository.addTweet(userId, tweetId, score);
        List<UUID> timeline = repository.getTimeline(userId, null, 10);

        // Then
        assertEquals(1, timeline.size());
        assertEquals(tweetId, timeline.getFirst());
    }

    @Test
    void shouldRetrieveTweetsInReverseOrder() {
        // Given - add tweets with different scores (timestamps)
        UUID tweet1 = UUID.randomUUID();
        UUID tweet2 = UUID.randomUUID();
        UUID tweet3 = UUID.randomUUID();
        
        long score1 = 1000L;
        long score2 = 2000L;
        long score3 = 3000L;

        repository.addTweet(userId, tweet1, score1);
        repository.addTweet(userId, tweet2, score2);
        repository.addTweet(userId, tweet3, score3);

        // When
        List<UUID> timeline = repository.getTimeline(userId, null, 10);

        // Then - newest (highest score) first
        assertEquals(3, timeline.size());
        assertEquals(tweet3, timeline.getFirst()); // newest
        assertEquals(tweet2, timeline.get(1));
        assertEquals(tweet1, timeline.getLast()); // oldest
    }

    @Test
    void shouldAddMultipleTweetsAtOnce() {
        // Given
        UUID tweet1 = UUID.randomUUID();
        UUID tweet2 = UUID.randomUUID();
        UUID tweet3 = UUID.randomUUID();

        List<TimelineRepository.TweetScore> tweets = List.of(
            new TimelineRepository.TweetScore(tweet1, 1000L),
            new TimelineRepository.TweetScore(tweet2, 2000L),
            new TimelineRepository.TweetScore(tweet3, 3000L)
        );

        // When
        repository.addTweets(userId, tweets);
        List<UUID> timeline = repository.getTimeline(userId, null, 10);

        // Then
        assertEquals(3, timeline.size());
        assertEquals(tweet3, timeline.getFirst()); // newest first
    }

    @Test
    void shouldRemoveTweet() {
        // Given
        UUID tweet1 = UUID.randomUUID();
        UUID tweet2 = UUID.randomUUID();
        
        repository.addTweet(userId, tweet1, 1000L);
        repository.addTweet(userId, tweet2, 2000L);
        assertEquals(2, repository.getTimeline(userId, null, 10).size());

        // When
        repository.removeTweet(userId, tweet1);

        // Then
        List<UUID> timeline = repository.getTimeline(userId, null, 10);
        assertEquals(1, timeline.size());
        assertEquals(tweet2, timeline.getFirst());
    }

    @Test
    void shouldRemoveTweetsByUser() {
        // Given
        UserId author1 = UserId.random();
        UserId author2 = UserId.random();
        
        UUID tweet1 = UUID.randomUUID();
        UUID tweet2 = UUID.randomUUID();
        UUID tweet3 = UUID.randomUUID();

        repository.addTweet(userId, tweet1, 1000L);
        repository.addTweet(userId, tweet2, 2000L);
        repository.addTweet(userId, tweet3, 3000L);
        
        // When - remove tweets by author1
        repository.removeTweetsByUser(userId, author1, List.of(tweet1, tweet2));

        // Then
        List<UUID> timeline = repository.getTimeline(userId, null, 10);
        assertEquals(1, timeline.size());
        assertEquals(tweet3, timeline.getFirst());
    }

    @Test
    void shouldPaginateWithCursor() {
        // Given
        UUID tweet1 = UUID.randomUUID();
        UUID tweet2 = UUID.randomUUID();
        UUID tweet3 = UUID.randomUUID();
        UUID tweet4 = UUID.randomUUID();

        repository.addTweet(userId, tweet1, 1000L);
        repository.addTweet(userId, tweet2, 2000L);
        repository.addTweet(userId, tweet3, 3000L);
        repository.addTweet(userId, tweet4, 4000L);

        // When - get first page
        List<UUID> firstPage = repository.getTimeline(userId, null, 2);
        assertEquals(2, firstPage.size());
        
        // Get second page using cursor (maxScore)
        Long cursor = 2000L; // score of tweet2
        List<UUID> secondPage = repository.getTimeline(userId, cursor, 2);

        // Then
        assertEquals(1, secondPage.size()); // tweet1 (score 1000 < cursor 2000)
        assertEquals(tweet1, secondPage.getFirst());
    }

    @Test
    void shouldTrimTimelineToMaxSize() {
        // Given
        int maxSize = 5;
        for (int i = 0; i < 10; i++) {
            repository.addTweet(userId, UUID.randomUUID(), i * 1000L);
        }

        // When
        repository.trimTimeline(userId, maxSize);
        List<UUID> timeline = repository.getTimeline(userId, null, 20);

        // Then - should only keep the newest 5 tweets
        assertEquals(maxSize, timeline.size());
    }

    @Test
    void shouldAutoTrimOnAdd() {
        // Given - set max size to 3 via AppProperties
        AppProperties.Timeline timelineProps = appProperties.getTimeline();
        int originalMaxSize = timelineProps.getMaxSize();
        timelineProps.setMaxSize(3);

        try {
            // Add 5 tweets - each addTweet call should auto-trim
            for (int i = 0; i < 5; i++) {
                repository.addTweet(userId, UUID.randomUUID(), i * 1000L);
            }

            // Then - should be trimmed to 3 (newest tweets kept)
            List<UUID> timeline = repository.getTimeline(userId, null, 10);
            assertEquals(3, timeline.size());
        } finally {
            // Restore original value
            timelineProps.setMaxSize(originalMaxSize);
        }
    }

    @Test
    void shouldReturnEmptyListForEmptyTimeline() {
        // When
        List<UUID> timeline = repository.getTimeline(userId, null, 10);

        // Then
        assertTrue(timeline.isEmpty());
    }

    @Test
    void shouldDeleteAllTimelines() {
        // Given
        UserId user1 = UserId.random();
        UserId user2 = UserId.random();
        
        repository.addTweet(user1, UUID.randomUUID(), 1000L);
        repository.addTweet(user2, UUID.randomUUID(), 2000L);

        // When
        long deleted = repository.deleteAll();

        // Then
        assertEquals(2, deleted);
        assertTrue(repository.getTimeline(user1, null, 10).isEmpty());
        assertTrue(repository.getTimeline(user2, null, 10).isEmpty());
    }
}
