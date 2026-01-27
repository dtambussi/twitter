package com.twitter.integration.e2e;

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
 * End-to-end integration tests that simulate complete user journeys.
 * Tests realistic multi-step scenarios that span multiple use cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("User Journey E2E Tests")
@SuppressWarnings("unchecked")
class UserJourneyIntegrationTest extends FullStackTestBase {

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
    @DisplayName("Complete social interaction: follow, tweet, unfollow journey")
    void completeSocialInteractionJourney() {
        // === Step 1: Bob creates some tweets ===
        String bobTweet1 = createTweet(bob, "Bob's first post!");
        String bobTweet2 = createTweet(bob, "Bob's second post!");
        processOutboxEvents();
        outboxRepository.deleteAll();

        // === Step 2: Charlie creates some tweets ===
        String charlieTweet1 = createTweet(charlie, "Charlie here!");
        processOutboxEvents();
        outboxRepository.deleteAll();

        // === Step 3: Alice follows Bob ===
        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Verify: Alice's timeline has Bob's tweets (backfilled)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(2, timeline.size(), "Alice should have Bob's 2 tweets");
        });

        // === Step 4: Bob creates another tweet ===
        String bobTweet3 = createTweet(bob, "Bob's third post!");
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Verify: Alice's timeline now has 3 tweets from Bob
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(3, timeline.size(), "Alice should have Bob's 3 tweets");
        });

        // === Step 5: Alice follows Charlie ===
        createFollow(alice, charlie);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Verify: Alice's timeline has tweets from both Bob and Charlie
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(4, timeline.size(), "Alice should have 3 Bob + 1 Charlie tweets");
        });

        // === Step 6: Alice unfollows Bob ===
        unfollow(alice, bob);
        processOutboxEvents();

        // Verify: Alice's timeline only has Charlie's tweet
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(1, timeline.size(), "Alice should only have Charlie's tweet");
            assertEquals(UUID.fromString(charlieTweet1), timeline.getFirst());
        });
    }

    @Test
    @DisplayName("Mutual follow interaction between users")
    void mutualFollowJourney() {
        // === Step 1: Alice and Bob follow each other ===
        createFollow(alice, bob);
        createFollow(bob, alice);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // === Step 2: Alice creates a tweet ===
        String aliceTweet = createTweet(alice, "Hello from Alice!");
        processOutboxEvents();
        outboxRepository.deleteAll();

        // === Step 3: Bob creates a tweet ===
        String bobTweet = createTweet(bob, "Hello from Bob!");
        processOutboxEvents();

        // Verify: Alice sees Bob's tweet
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> aliceTimeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(1, aliceTimeline.size());
            assertEquals(UUID.fromString(bobTweet), aliceTimeline.getFirst());
        });

        // Verify: Bob sees Alice's tweet
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> bobTimeline = timelineRepository.getTimeline(bob, null, 10);
            assertEquals(1, bobTimeline.size());
            assertEquals(UUID.fromString(aliceTweet), bobTimeline.getFirst());
        });
    }

    @Test
    @DisplayName("New user onboarding: first tweet and first follow")
    void newUserOnboardingJourney() {
        // === Step 1: Alice is a new user, her timeline is empty ===
        ResponseEntity<Map> emptyTimeline = getTimeline(alice, null, 10);
        assertEquals(HttpStatus.OK, emptyTimeline.getStatusCode());
        assertTrue(((List<?>) Objects.requireNonNull(emptyTimeline.getBody()).get("data")).isEmpty());

        // === Step 2: Alice creates her first tweet ===
        String aliceFirstTweet = createTweet(alice, "My first tweet ever!");
        processOutboxEvents();

        // Verify: Tweet stored, user auto-created
        assertEquals(1, tweetRepository.count());
        assertTrue(userRepository.exists(alice));

        // === Step 3: Alice follows Bob (who has existing tweets) ===
        createTweet(bob, "Bob's existing tweet");
        processOutboxEvents();
        outboxRepository.deleteAll();

        createFollow(alice, bob);
        processOutboxEvents();

        // Verify: Alice's timeline has Bob's tweet
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(1, timeline.size(), "Alice should see Bob's tweet");
        });
    }

    @Test
    @DisplayName("High activity: multiple users posting and following")
    void highActivityJourney() {
        // === Setup: Create a small social network ===
        createFollow(alice, bob);
        createFollow(alice, charlie);
        createFollow(bob, alice);
        createFollow(bob, charlie);
        createFollow(charlie, alice);
        createFollow(charlie, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // === Everyone posts tweets ===
        createTweet(alice, "Alice tweet 1");
        createTweet(bob, "Bob tweet 1");
        createTweet(charlie, "Charlie tweet 1");
        createTweet(alice, "Alice tweet 2");
        createTweet(bob, "Bob tweet 2");
        processOutboxEvents();

        // === Verify everyone sees the right tweets ===
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> aliceTimeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(3, aliceTimeline.size(), "Alice should see Bob(2) + Charlie(1) = 3 tweets");

            List<UUID> bobTimeline = timelineRepository.getTimeline(bob, null, 10);
            assertEquals(3, bobTimeline.size(), "Bob should see Alice(2) + Charlie(1) = 3 tweets");

            List<UUID> charlieTimeline = timelineRepository.getTimeline(charlie, null, 10);
            assertEquals(4, charlieTimeline.size(), "Charlie should see Alice(2) + Bob(2) = 4 tweets");
        });
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

    private void unfollow(UserId follower, UserId followee) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", follower.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.exchange(
                "/api/v1/users/" + follower + "/follow/" + followee,
                HttpMethod.DELETE,
                request,
                Map.class
        );
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
