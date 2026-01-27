package com.twitter.integration.usecase;

import com.twitter.adapter.out.persistence.JdbcTweetRepository;
import com.twitter.adapter.out.persistence.JdbcUserRepository;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the GetUserTweets use case.
 * Tests: HTTP → TweetService → Database → Response
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("GetUserTweets Use Case")
@SuppressWarnings({"unchecked", "rawtypes"})
class GetUserTweetsUseCaseIntegrationTest extends FullStackTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcTweetRepository tweetRepository;

    private UserId alice;

    @BeforeEach
    void setUp() {
        tweetRepository.deleteAll();
        userRepository.deleteAll();
        alice = UserId.random();
    }

    @Test
    @DisplayName("Should return user's tweets")
    void shouldReturnUserTweets() {
        // Given - Alice creates some tweets
        createTweet(alice, "Tweet 1");
        createTweet(alice, "Tweet 2");
        createTweet(alice, "Tweet 3");

        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/tweets",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertEquals(3, items.size());
    }

    @Test
    @DisplayName("Should return tweets ordered newest first")
    void shouldOrderTweetsNewestFirst() throws InterruptedException {
        // Given - Alice creates tweets with slight delays
        String tweet1 = createTweet(alice, "First tweet");
        Thread.sleep(10);
        String tweet2 = createTweet(alice, "Second tweet");
        Thread.sleep(10);
        String tweet3 = createTweet(alice, "Third tweet");

        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/tweets?limit=10",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertEquals(tweet3, items.get(0).get("id")); // Newest first
        assertEquals(tweet2, items.get(1).get("id"));
        assertEquals(tweet1, items.get(2).get("id"));
    }

    @Test
    @DisplayName("Should paginate with cursor")
    void shouldPaginateWithCursor() {
        // Given - Alice creates many tweets
        for (int i = 0; i < 15; i++) {
            createTweet(alice, "Tweet " + i);
        }

        // When - Get first page
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> firstPage = restTemplate.exchange(
                "/api/v1/users/" + alice + "/tweets?limit=10",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        List<Map> firstItems = (List<Map>) Objects.requireNonNull(firstPage.getBody()).get("data");
        assertEquals(10, firstItems.size());
        Map pagination = (Map) Objects.requireNonNull(firstPage.getBody()).get("pagination");
        String nextCursor = (String) pagination.get("nextCursor");
        assertNotNull(nextCursor);

        // Get second page
        ResponseEntity<Map> secondPage = restTemplate.exchange(
                "/api/v1/users/" + alice + "/tweets?limit=10&cursor=" + nextCursor,
                HttpMethod.GET,
                request,
                Map.class
        );

        List<Map> secondItems = (List<Map>) Objects.requireNonNull(secondPage.getBody()).get("data");
        assertEquals(5, secondItems.size());
    }

    @Test
    @DisplayName("Should return empty list for user with no tweets")
    void shouldReturnEmptyForUserWithNoTweets() {
        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/tweets",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertTrue(items.isEmpty());
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
}
