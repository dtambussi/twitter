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
 * Integration tests for the UnfollowUser use case.
 * Tests: HTTP → FollowService → Database → Outbox → Kafka → Consumer → Timeline cleanup
 */
@SuppressWarnings("rawtypes")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("UnfollowUser Use Case")
class UnfollowUserUseCaseIntegrationTest extends FullStackTestBase {

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

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        timelineRepository.deleteAll();
        followRepository.deleteAll();
        tweetRepository.deleteAll();
        userRepository.deleteAll();

        alice = UserId.random();
        bob = UserId.random();
    }

    @Test
    @DisplayName("Should remove follow relationship and create outbox entry")
    void shouldUnfollowUserAndCreateOutboxEntry() {
        // Given - Alice follows Bob
        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/follow/" + bob,
                HttpMethod.DELETE,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(followRepository.exists(alice, bob));
        assertEquals(1, outboxRepository.countUnprocessed());
    }

    @Test
    @DisplayName("Should remove unfollowed user's tweets from timeline")
    void shouldRemoveTweetsFromTimelineOnUnfollow() {
        // Given - Bob has tweets, Alice follows Bob
        createTweet(bob, "Bob's tweet 1");
        createTweet(bob, "Bob's tweet 2");
        processOutboxEvents();
        outboxRepository.deleteAll();

        createFollow(alice, bob);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Verify Alice has Bob's tweets
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(2, timeline.size());
        });

        // When - Alice unfollows Bob
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.exchange(
                "/api/v1/users/" + alice + "/follow/" + bob,
                HttpMethod.DELETE,
                request,
                Map.class
        );
        processOutboxEvents();

        // Then - Bob's tweets removed from Alice's timeline
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(0, timeline.size(), "Bob's tweets should be removed");
        });
    }

    @Test
    @DisplayName("Should reject unfollow when not following")
    void shouldRejectUnfollowWhenNotFollowing() {
        // When - Alice tries to unfollow Bob without following first
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/follow/" + bob,
                HttpMethod.DELETE,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    @DisplayName("Should not affect other followed users' tweets on unfollow")
    void shouldNotAffectOtherUsersOnUnfollow() {
        // Given - Alice follows both Bob and Charlie
        UserId charlie = UserId.random();
        
        createTweet(bob, "Bob's tweet");
        createTweet(charlie, "Charlie's tweet");
        processOutboxEvents();
        outboxRepository.deleteAll();

        createFollow(alice, bob);
        createFollow(alice, charlie);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Verify Alice has both users' tweets
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(2, timeline.size());
        });

        // When - Alice unfollows Bob only
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.exchange(
                "/api/v1/users/" + alice + "/follow/" + bob,
                HttpMethod.DELETE,
                request,
                Map.class
        );
        processOutboxEvents();

        // Then - Only Bob's tweets removed, Charlie's remain
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(alice, null, 10);
            assertEquals(1, timeline.size(), "Only Charlie's tweet should remain");
        });
    }

    // ==================== Helper Methods ====================

    private void createTweet(UserId userId, String content) {
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
