package com.twitter.integration.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twitter.adapter.out.messaging.OutboxPoller;
import com.twitter.adapter.out.persistence.JdbcOutboxRepository;
import com.twitter.adapter.out.persistence.JdbcTweetRepository;
import com.twitter.adapter.out.persistence.JdbcUserRepository;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.domain.event.TweetCreated;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Outbox Pattern.
 * Verifies reliable event publishing: Database → Outbox → Kafka
 */
@SpringBootTest
@EnabledIf("isDockerAvailable")
@DisplayName("Outbox Pattern E2E Tests")
class OutboxPatternIntegrationTest extends FullStackTestBase {

    @Autowired
    private JdbcOutboxRepository outboxRepository;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcTweetRepository tweetRepository;

    @Autowired
    private TimelineRepository timelineRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxPoller outboxPoller;

    private UserId alice;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        timelineRepository.deleteAll();
        tweetRepository.deleteAll();
        userRepository.deleteAll();

        alice = UserId.random();
        userRepository.upsert(User.create(alice));
    }

    @Test
    @DisplayName("Events are saved to outbox and marked unprocessed")
    void shouldSaveEventToOutbox() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID tweetId = UUID.randomUUID();
        TweetCreated event = TweetCreated.from(eventId, tweetId, alice, "Test tweet");

        // When
        outboxRepository.save(event, "test-request-id");

        // Then
        assertEquals(1, outboxRepository.countUnprocessed());
        var entries = outboxRepository.findUnprocessedWithLock(10);
        assertEquals(1, entries.size());
        assertEquals("TWEET_CREATED", entries.getFirst().eventType());
        assertEquals(alice.toString(), entries.getFirst().aggregateId());
    }

    @Test
    @DisplayName("OutboxPoller processes events and marks them as processed")
    void shouldProcessOutboxEvents() {
        // Given - save event to outbox
        UUID eventId = UUID.randomUUID();
        UUID tweetId = UUID.randomUUID();
        TweetCreated event = TweetCreated.from(eventId, tweetId, alice, "Test tweet");
        outboxRepository.save(event, "test-request-id");

        assertEquals(1, outboxRepository.countUnprocessed());

        // When - poll and publish
        outboxPoller.pollAndPublish();

        // Then - should be marked as processed
        assertEquals(0, outboxRepository.countUnprocessed());
    }

    @Test
    @DisplayName("Direct Kafka publishing works")
    void shouldPublishDirectlyToKafka() throws Exception {
        // Given
        UUID tweetId = UUID.randomUUID();
        TweetCreated event = TweetCreated.from(UUID.randomUUID(), tweetId, alice, "Test tweet");
        String payload = objectMapper.writeValueAsString(event);

        // When
        var result = kafkaTemplate.send("twitter.events", alice.toString(), payload).get();

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecordMetadata());
        assertEquals("twitter.events", result.getRecordMetadata().topic());
    }

    @Test
    @DisplayName("Full outbox flow: save → poll → publish to Kafka")
    void shouldCompleteFullOutboxFlow() throws Exception {
        // Given - save event to outbox
        UUID eventId = UUID.randomUUID();
        UUID tweetId = UUID.randomUUID();
        TweetCreated event = TweetCreated.from(eventId, tweetId, alice, "Test tweet");
        outboxRepository.save(event, "test-request-id");

        assertEquals(1, outboxRepository.countUnprocessed());

        // When - simulate OutboxPoller: retrieve, publish, mark processed
        var entries = outboxRepository.findUnprocessedWithLock(10);
        assertEquals(1, entries.size());

        String payload = entries.getFirst().payload();
        var result = kafkaTemplate.send("twitter.events", entries.getFirst().aggregateId(), payload).get();

        outboxRepository.markAsProcessed(List.of(entries.getFirst().id()));

        // Then
        assertNotNull(result);
        assertEquals("twitter.events", result.getRecordMetadata().topic());
        assertEquals(0, outboxRepository.countUnprocessed());
    }

    @Test
    @DisplayName("Multiple events are processed in batch")
    void shouldProcessMultipleEventsInBatch() {
        // Given - save multiple events
        for (int i = 0; i < 5; i++) {
            TweetCreated event = TweetCreated.from(UUID.randomUUID(), UUID.randomUUID(), alice, "Tweet " + i);
            outboxRepository.save(event, "test-request-id");
        }

        assertEquals(5, outboxRepository.countUnprocessed());

        // When
        outboxPoller.pollAndPublish();

        // Then
        assertEquals(0, outboxRepository.countUnprocessed());
    }
}
