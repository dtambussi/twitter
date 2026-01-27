package com.twitter.integration;

import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke tests to verify the application context loads correctly
 * and essential endpoints respond.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("isDockerAvailable")
class TwitterApplicationContextTest extends FullStackTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Context loading is verified by Spring Boot - if this test runs, context loaded successfully
    }

    @Test
    void healthEndpointShouldRespond() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void statsEndpointShouldRespond() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/demo/stats", String.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
