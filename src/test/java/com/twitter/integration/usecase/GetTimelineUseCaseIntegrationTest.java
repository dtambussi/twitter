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
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the GetTimeline use case.
 * Tests: HTTP → TimelineService → Redis (fan-out on write) + Database (fan-out on read) → Response
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("GetTimeline Use Case")
@SuppressWarnings({"unchecked", "rawtypes"})
class GetTimelineUseCaseIntegrationTest extends FullStackTestBase {

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
    @DisplayName("Should return tweets from followed users")
    void shouldReturnTweetsFromFollowedUsers() {
        // Given - Alice follows Bob and Charlie
        createFollow(alice, bob);
        createFollow(alice, charlie);
        processOutboxEvents();
        outboxRepository.deleteAll();

        createTweet(bob, "Bob's tweet");
        createTweet(charlie, "Charlie's tweet");
        processOutboxEvents();

        // When
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<Map> response = getTimeline(alice, null, 10);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
            assertEquals(2, items.size());
        });
    }

    @Test
    @DisplayName("Should return tweets ordered by newest first")
    void shouldOrderTimelineNewestFirst() throws InterruptedException {
        // Given - Alice follows Bob
        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Create tweets with delay to ensure different timestamps
        String tweet1 = createTweet(bob, "First tweet");
        Thread.sleep(10);
        String tweet2 = createTweet(bob, "Second tweet");
        Thread.sleep(10);
        String tweet3 = createTweet(bob, "Third tweet");
        processOutboxEvents();

        // When/Then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<Map> response = getTimeline(alice, null, 10);

            List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
            assertEquals(3, items.size());
            // Newest first
            assertEquals(tweet3, items.get(0).get("id"));
            assertEquals(tweet2, items.get(1).get("id"));
            assertEquals(tweet1, items.get(2).get("id"));
        });
    }

    @Test
    @DisplayName("Should paginate with cursor")
    void shouldPaginateTimeline() {
        // Given - Alice follows Bob, Bob creates many tweets
        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        for (int i = 0; i < 15; i++) {
            createTweet(bob, "Tweet " + i);
        }
        processOutboxEvents();

        // When/Then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // First page
            ResponseEntity<Map> firstPage = getTimeline(alice, null, 10);

            List<Map> items = (List<Map>) Objects.requireNonNull(firstPage.getBody()).get("data");
            assertEquals(10, items.size());
            Map pagination = (Map) Objects.requireNonNull(firstPage.getBody()).get("pagination");
            String nextCursor = (String) pagination.get("nextCursor");
            assertNotNull(nextCursor, "Should have next cursor");

            // Second page
            ResponseEntity<Map> secondPage = getTimeline(alice, nextCursor, 10);

            List<Map> secondItems = (List<Map>) Objects.requireNonNull(secondPage.getBody()).get("data");
            assertEquals(5, secondItems.size());
        });
    }

    @Test
    @DisplayName("Should return empty list for new user with no follows")
    void shouldReturnEmptyTimelineForNewUser() {
        // When
        ResponseEntity<Map> response = getTimeline(alice, null, 10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertTrue(items.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list when followed users have no tweets")
    void shouldReturnEmptyWhenFollowedUsersHaveNoTweets() {
        // Given - Alice follows Bob, but Bob has no tweets
        createFollow(alice, bob);
        processOutboxEvents();

        // When
        ResponseEntity<Map> response = getTimeline(alice, null, 10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertTrue(items.isEmpty());
    }

    @Test
    @DisplayName("Should not include own tweets in timeline")
    void shouldNotIncludeOwnTweets() {
        // Given - Alice creates a tweet
        createTweet(alice, "Alice's own tweet");
        processOutboxEvents();

        // When - Alice views her timeline
        ResponseEntity<Map> response = getTimeline(alice, null, 10);

        // Then - Her own tweet should NOT be in her timeline
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertTrue(items.isEmpty(), "Own tweets should not appear in timeline");
    }

    @Test
    @DisplayName("Should respect limit parameter")
    void shouldRespectLimitParameter() {
        // Given - Alice follows Bob, Bob has many tweets
        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        for (int i = 0; i < 10; i++) {
            createTweet(bob, "Tweet " + i);
        }
        processOutboxEvents();

        // When - Request with limit=5
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<Map> response = getTimeline(alice, null, 5);

            List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
            assertEquals(5, items.size());
            Map pagination = (Map) Objects.requireNonNull(response.getBody()).get("pagination");
            assertNotNull(pagination.get("nextCursor"));
        });
    }

    @Test
    @DisplayName("Should reject viewing another user's timeline")
    void shouldRejectViewingOtherUsersTimeline() {
        // When - Alice tries to view Bob's timeline
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + bob + "/timeline?limit=10",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then - Should be forbidden
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("FORBIDDEN", Objects.requireNonNull(response.getBody()).get("error"));
    }

    // ==================== Helper Methods ====================

    private ResponseEntity<Map> getTimeline(UserId userId, String cursor, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        String url = "/api/v1/users/" + userId + "/timeline?limit=" + limit;
        if (cursor != null) {
            url += "&cursor=" + cursor;
        }

        return restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
    }

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
