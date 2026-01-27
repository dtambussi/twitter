package com.twitter.adapter.out.persistence;

import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.id.UUIDv7Generator;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIf("isDockerAvailable")
class JdbcTweetRepositoryTest extends FullStackTestBase {

    @Autowired
    private JdbcTweetRepository tweetRepository;

    @Autowired
    private JdbcUserRepository userRepository;

    private UserId userId;

    @BeforeEach
    void setUpTestUser() {
        // Create test user (database is cleaned by parent @BeforeEach)
        userId = UserId.random();
        userRepository.upsert(User.create(userId));
    }

    @Test
    void shouldSaveAndFindTweet() {
        // Given
        UUID tweetId = UUID.randomUUID();
        Tweet tweet = new Tweet(tweetId, userId, "Hello, world!", java.time.Instant.now());

        // When
        tweetRepository.save(tweet);
        Optional<Tweet> found = tweetRepository.findById(tweetId);

        // Then
        assertTrue(found.isPresent());
        assertEquals(tweetId, found.get().id());
        assertEquals(userId, found.get().userId());
        assertEquals("Hello, world!", found.get().content());
    }

    @Test
    void shouldFindTweetsByUserId() {
        // Given
        UserId anotherUserId = UserId.random();
        userRepository.upsert(User.create(anotherUserId));

        Tweet tweet1 = new Tweet(UUID.randomUUID(), userId, "First tweet", java.time.Instant.now());
        Tweet tweet2 = new Tweet(UUID.randomUUID(), userId, "Second tweet", java.time.Instant.now());
        Tweet tweet3 = new Tweet(UUID.randomUUID(), anotherUserId, "Other user tweet", java.time.Instant.now());

        tweetRepository.save(tweet1);
        tweetRepository.save(tweet2);
        tweetRepository.save(tweet3);

        // When
        List<Tweet> userTweets = tweetRepository.findByUserId(userId, null, 10);

        // Then
        assertEquals(2, userTweets.size());
        assertTrue(userTweets.stream().allMatch(t -> t.userId().equals(userId)));
    }

    @Test
    void shouldFindTweetsWithCursor() throws InterruptedException {
        // Given - use UUIDv7 for time-ordered IDs (required for cursor pagination)
        UUIDv7Generator idGenerator = new UUIDv7Generator();
        
        Tweet tweet1 = new Tweet(idGenerator.generate(), userId, "First", java.time.Instant.now());
        Thread.sleep(2); // Ensure distinct timestamps
        Tweet tweet2 = new Tweet(idGenerator.generate(), userId, "Second", java.time.Instant.now());
        Thread.sleep(2);
        Tweet tweet3 = new Tweet(idGenerator.generate(), userId, "Third", java.time.Instant.now());

        tweetRepository.save(tweet1);
        tweetRepository.save(tweet2);
        tweetRepository.save(tweet3);

        // When - get first page (newest first, so tweet3, tweet2)
        List<Tweet> firstPage = tweetRepository.findByUserId(userId, null, 2);
        assertEquals(2, firstPage.size());

        // Get second page using cursor
        UUID cursor = firstPage.getLast().id();
        List<Tweet> secondPage = tweetRepository.findByUserId(userId, cursor, 2);

        // Then - should get tweet1 (older than cursor)
        assertEquals(1, secondPage.size());
        assertTrue(secondPage.getFirst().id().compareTo(cursor) < 0);
    }

    @Test
    void shouldFindTweetsByIds() {
        // Given
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        tweetRepository.save(new Tweet(id1, userId, "Tweet 1", java.time.Instant.now()));
        tweetRepository.save(new Tweet(id2, userId, "Tweet 2", java.time.Instant.now()));
        tweetRepository.save(new Tweet(id3, userId, "Tweet 3", java.time.Instant.now()));

        // When
        List<Tweet> found = tweetRepository.findByIds(List.of(id1, id3));

        // Then
        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(t -> t.id().equals(id1)));
        assertTrue(found.stream().anyMatch(t -> t.id().equals(id3)));
    }

    @Test
    void shouldReturnEmptyListForEmptyIds() {
        // When
        List<Tweet> found = tweetRepository.findByIds(List.of());

        // Then
        assertTrue(found.isEmpty());
    }

    @Test
    void shouldCountTweets() {
        // Given
        assertEquals(0, tweetRepository.count());

        // When
        tweetRepository.save(new Tweet(UUID.randomUUID(), userId, "Tweet 1", java.time.Instant.now()));
        tweetRepository.save(new Tweet(UUID.randomUUID(), userId, "Tweet 2", java.time.Instant.now()));

        // Then
        assertEquals(2, tweetRepository.count());
    }
}
