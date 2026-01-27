package com.twitter.integration.e2e;

import com.twitter.adapter.out.messaging.OutboxPoller;
import com.twitter.adapter.out.persistence.JdbcFollowRepository;
import com.twitter.adapter.out.persistence.JdbcOutboxRepository;
import com.twitter.adapter.out.persistence.JdbcTweetRepository;
import com.twitter.adapter.out.persistence.JdbcUserRepository;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.domain.model.Follow;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
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
 * Integration tests for timeline fan-out strategies.
 * Tests fan-out on write (normal users) vs fan-out on read (celebrities).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("Timeline Fan-out Strategy E2E Tests")
@SuppressWarnings({"unchecked", "rawtypes"})
class TimelineFanoutIntegrationTest extends FullStackTestBase {

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

    @Autowired
    private AppProperties appProperties;

    private UserId normalUser;
    private UserId celebrity;
    private UserId follower;

    @BeforeEach
    void setUp() {
        // Create test user IDs
        normalUser = UserId.random();
        celebrity = UserId.random();
        follower = UserId.random();
        
        // Insert users into database (needed for direct repository calls)
        userRepository.upsert(User.create(normalUser));
        userRepository.upsert(User.create(celebrity));
        userRepository.upsert(User.create(follower));
    }

    @Test
    @DisplayName("Normal user tweets use fan-out on write (cached in Redis)")
    void normalUserTweetsUseFanOutOnWrite() {
        // Given - follower follows normalUser
        createFollow(follower, normalUser);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // When - normalUser creates a tweet
        String tweetId = createTweet(normalUser, "Normal user tweet");
        processOutboxEvents();

        // Then - Tweet should be in follower's Redis timeline (fan-out on write)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline = timelineRepository.getTimeline(follower, null, 10);
            assertEquals(1, timeline.size(), "Tweet should be cached in Redis");
            assertEquals(UUID.fromString(tweetId), timeline.getFirst());
        });
    }

    @Test
    @DisplayName("Celebrity tweets skip fan-out on write")
    void celebrityTweetsSkipFanOutOnWrite() {
        // Given - Make celebrity a celebrity (many followers)
        int threshold = appProperties.getTimeline().getCelebrityFollowerThreshold();
        createCelebrityWithFollowers(celebrity, threshold + 100);
        
        // follower also follows celebrity
        createFollow(follower, celebrity);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // When - celebrity creates a tweet
        createTweet(celebrity, "Celebrity tweet");
        processOutboxEvents();

        // Then - Tweet should NOT be in follower's Redis timeline (fan-out on read)
        // Give some time for potential fan-out to complete
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        
        List<UUID> timeline = timelineRepository.getTimeline(follower, null, 10);
        assertTrue(timeline.isEmpty(), "Celebrity tweet should NOT be cached in Redis (fan-out on read)");
    }

    @Test
    @DisplayName("Timeline API merges cached tweets with celebrity tweets")
    void timelineApiMergesCachedAndCelebrityTweets() {
        // Given - Setup celebrity with many followers
        int threshold = appProperties.getTimeline().getCelebrityFollowerThreshold();
        createCelebrityWithFollowers(celebrity, threshold + 100);

        // follower follows both normal user and celebrity
        createFollow(follower, normalUser);
        createFollow(follower, celebrity);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // Both create tweets
        createTweet(normalUser, "Normal tweet");
        createTweet(celebrity, "Celebrity tweet");
        processOutboxEvents();

        // When - Get timeline via API
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", follower.toString());
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "/api/v1/users/" + follower + "/timeline?limit=10",
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            // Then - Should have both tweets (merged)
            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
            assertEquals(2, items.size(), "Should have both normal and celebrity tweets");
        });
    }

    @Test
    @DisplayName("User becomes celebrity when crossing follower threshold")
    void userBecomesCelebrityWhenCrossingThreshold() {
        // Given - normalUser starts with followers below threshold
        int threshold = appProperties.getTimeline().getCelebrityFollowerThreshold();
        
        // Create followers just below threshold
        for (int i = 0; i < threshold - 1; i++) {
            UserId f = UserId.random();
            userRepository.upsert(User.create(f));
            followRepository.save(Follow.create(f, normalUser).getOrThrow());
        }

        // follower follows normalUser
        createFollow(follower, normalUser);
        processOutboxEvents();
        outboxRepository.deleteAll();

        // First tweet - should use fan-out on write (still at threshold)
        createTweet(normalUser, "Tweet while normal");
        processOutboxEvents();

        // Wait for async fan-out to complete
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<UUID> timeline1 = timelineRepository.getTimeline(follower, null, 10);
            assertEquals(1, timeline1.size(), "First tweet should be cached");
        });

        // Add more followers to cross threshold
        for (int i = 0; i < 10; i++) {
            UserId f = UserId.random();
            userRepository.upsert(User.create(f));
            followRepository.save(Follow.create(f, normalUser).getOrThrow());
        }
        outboxRepository.deleteAll();

        // Second tweet - should skip fan-out (now a celebrity with > threshold followers)
        createTweet(normalUser, "Tweet as celebrity");
        processOutboxEvents();

        // Give time for potential (but expected to NOT happen) fan-out
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        List<UUID> timeline2 = timelineRepository.getTimeline(follower, null, 10);
        assertEquals(1, timeline2.size(), "Second tweet should NOT be cached (celebrity)");
    }

    // ==================== Helper Methods ====================

    private void createCelebrityWithFollowers(UserId celebrityId, int followerCount) {
        userRepository.upsert(User.create(celebrityId));
        
        for (int i = 0; i < followerCount; i++) {
            UserId f = UserId.random();
            userRepository.upsert(User.create(f));
            followRepository.save(Follow.create(f, celebrityId).getOrThrow());
        }
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
