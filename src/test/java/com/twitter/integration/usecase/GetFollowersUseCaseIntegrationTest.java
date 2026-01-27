package com.twitter.integration.usecase;

import com.twitter.adapter.out.persistence.JdbcFollowRepository;
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
 * Integration tests for the GetFollowers use case.
 * Tests: HTTP → FollowService → Database → Response
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
@DisplayName("GetFollowers Use Case")
@SuppressWarnings({"unchecked", "rawtypes"})
class GetFollowersUseCaseIntegrationTest extends FullStackTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcFollowRepository followRepository;

    private UserId alice;
    private UserId bob;
    private UserId charlie;

    @BeforeEach
    void setUp() {
        followRepository.deleteAll();
        userRepository.deleteAll();
        
        alice = UserId.random();
        bob = UserId.random();
        charlie = UserId.random();
    }

    @Test
    @DisplayName("Should return user's followers")
    void shouldReturnFollowers() {
        // Given - Bob and Charlie follow Alice
        createFollow(bob, alice);
        createFollow(charlie, alice);

        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/followers",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertEquals(2, items.size());
    }

    @Test
    @DisplayName("Should return empty list when no followers")
    void shouldReturnEmptyWhenNoFollowers() {
        // When
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + alice + "/followers",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map> items = (List<Map>) Objects.requireNonNull(response.getBody()).get("data");
        assertTrue(items.isEmpty());
    }

    @Test
    @DisplayName("Should paginate followers")
    void shouldPaginateFollowers() {
        // Given - Create many followers
        for (int i = 0; i < 15; i++) {
            UserId follower = UserId.random();
            createFollow(follower, alice);
        }

        // When - Get first page
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", alice.toString());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> firstPage = restTemplate.exchange(
                "/api/v1/users/" + alice + "/followers?limit=10",
                HttpMethod.GET,
                request,
                Map.class
        );

        // Then
        List<Map> firstItems = (List<Map>) Objects.requireNonNull(firstPage.getBody()).get("data");
        assertEquals(10, firstItems.size());
        Map pagination = (Map) Objects.requireNonNull(firstPage.getBody()).get("pagination");
        assertNotNull(pagination.get("nextCursor"));
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
}
