package com.twitter.integration.usecase;

import com.twitter.adapter.out.messaging.OutboxPoller;
import com.twitter.adapter.out.persistence.JdbcFollowRepository;
import com.twitter.adapter.out.persistence.JdbcOutboxRepository;
import com.twitter.adapter.out.persistence.JdbcTweetRepository;
import com.twitter.adapter.out.persistence.JdbcUserRepository;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.domain.model.UserId;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the CreateTweet use case.
 * Tests: HTTP → TweetService → Database → Outbox → Kafka → Consumer → Timeline fan-out
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("CreateTweet Use Case")
@SuppressWarnings("unchecked")
class CreateTweetUseCaseIntegrationTest extends FullStackTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcTweetRepository tweetRepository;

    @Autowired
    private JdbcFollowRepository followRepository;

    @Autowired
    private JdbcOutboxRepository outboxRepository;

    @Autowired
    private TimelineRepository timelineRepository;

    @Autowired
    private OutboxPoller outboxPoller;

    private UserId alice;
    private UserId bob;
    private UserId charlie;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        timelineRepository.deleteAll();
        followRepository.deleteAll();
        tweetRepository.deleteAll();
        userRepository.deleteAll();

        alice = UserId.random();
        bob = UserId.random();
        charlie = UserId.random();
    }

    @Test
    @DisplayName("Should create tweet, store in database, and create outbox entry")
    void shouldCreateTweetAndOutboxEntry() {
        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(
                """
                {"content": "Hello, World!"}
                """,
                headers
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/tweets",
                request,
                Map.class
        );

        // Then - HTTP response
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(Objects.requireNonNull(response.getBody()).get("id"));
        assertEquals("Hello, World!", Objects.requireNonNull(response.getBody()).get("content"));

        // Then - database
        assertEquals(1, tweetRepository.count());

        // Then - outbox
        assertEquals(1, outboxRepository.countUnprocessed());
    }

    @Test
    @DisplayName("Should reject tweet over 280 characters")
    void shouldRejectTweetOver280Characters() {
        // Given
        String longContent = "x".repeat(281);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(
                String.format("{\"content\": \"%s\"}", longContent),
                headers
        );

        // When
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/tweets",
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, tweetRepository.count());
    }

    @Test
    @DisplayName("Should reject empty tweet")
    void shouldRejectEmptyTweet() {
        // Given
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(
                """
                {"content": ""}
                """,
                headers
        );

        // When
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/tweets",
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, tweetRepository.count());
    }

    @Test
    @DisplayName("Should fan-out tweet to all followers' timelines")
    void shouldFanOutTweetToFollowerTimelines() {
        // Given - Bob and Charlie follow Alice
        createFollow(bob, alice);
        createFollow(charlie, alice);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // When - Alice creates a tweet
        String tweetId = createTweet(alice, "Alice's tweet");
        processOutboxEvents();

        // Then - Tweet appears in Bob's and Charlie's timelines
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> bobTimeline = timelineRepository.getTimeline(bob, null, 10);
            List<UUID> charlieTimeline = timelineRepository.getTimeline(charlie, null, 10);

            assertEquals(1, bobTimeline.size(), "Bob should have Alice's tweet");
            assertEquals(1, charlieTimeline.size(), "Charlie should have Alice's tweet");
            assertEquals(UUID.fromString(tweetId), bobTimeline.getFirst());
            assertEquals(UUID.fromString(tweetId), charlieTimeline.getFirst());
        });
    }

    @Test
    @DisplayName("Should not add tweet to non-followers' timelines")
    void shouldNotFanOutToNonFollowers() {
        // Given - Only Bob follows Alice (Charlie does not)
        createFollow(bob, alice);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // When - Alice creates a tweet
        createTweet(alice, "Alice's tweet");
        processOutboxEvents();

        // Then - Tweet appears only in Bob's timeline, not Charlie's
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> bobTimeline = timelineRepository.getTimeline(bob, null, 10);
            List<UUID> charlieTimeline = timelineRepository.getTimeline(charlie, null, 10);

            assertEquals(1, bobTimeline.size(), "Bob should have Alice's tweet");
            assertEquals(0, charlieTimeline.size(), "Charlie should NOT have Alice's tweet");
        });
    }

    // ==================== Helper Methods ====================

    private String createTweet(UserId userId, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(
                String.format("{\"content\": \"%s\"}", content),
                headers
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/tweets",
                request,
                Map.class
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return (String) Objects.requireNonNull(response.getBody()).get("id");
    }

    private void createFollow(UserId follower, UserId followee) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", follower.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/users/" + follower + "/follow/" + followee,
                request,
                Map.class
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    private void processOutboxEvents() {
        outboxPoller.pollAndPublish();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
