package com.twitter.adapter.out.messaging;

import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.OutboxRepository.OutboxEntry;
import com.twitter.infrastructure.config.AppProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxPoller.
 * Tests the outbox polling and Kafka publishing logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPoller")
@SuppressWarnings("unchecked")
class OutboxPollerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private MetricsPort metrics;

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setOutbox(new AppProperties.Outbox());
        appProperties.getOutbox().setBatchSize(100);
        appProperties.setKafka(new AppProperties.Kafka());
        appProperties.getKafka().setTopic("twitter.events");

        outboxPoller = new OutboxPoller(outboxRepository, kafkaTemplate, appProperties, metrics);
    }

    @Nested
    @DisplayName("pollAndPublish")
    class PollAndPublishTests {

        @Test
        @DisplayName("Should do nothing when no entries")
        void shouldDoNothingWhenNoEntries() {
            // Given
            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(List.of());

            // When
            outboxPoller.pollAndPublish();

            // Then
            verifyNoInteractions(kafkaTemplate);
            verify(outboxRepository, never()).markAsProcessed(any());
        }

        @Test
        @DisplayName("Should publish entries to Kafka")
        void shouldPublishEntriesToKafka() {
            // Given
            UUID entryId = UUID.randomUUID();
            OutboxEntry entry = new OutboxEntry(
                    entryId, "TWEET_CREATED", "user-123",
                    "{\"tweetId\":\"123\"}", "request-1"
            );

            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(List.of(entry));

            // When
            outboxPoller.pollAndPublish();

            // Then
            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            ProducerRecord<String, String> record = captor.getValue();
            assertEquals("twitter.events", record.topic());
            assertEquals("user-123", record.key());
            assertEquals("{\"tweetId\":\"123\"}", record.value());
        }

        @Test
        @DisplayName("Should add headers to Kafka record")
        void shouldAddHeadersToKafkaRecord() {
            // Given
            UUID entryId = UUID.randomUUID();
            OutboxEntry entry = new OutboxEntry(
                    entryId, "TWEET_CREATED", "user-123",
                    "{}", "request-1"
            );

            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(List.of(entry));

            // When
            outboxPoller.pollAndPublish();

            // Then
            ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(kafkaTemplate).send(captor.capture());

            ProducerRecord<String, String> record = captor.getValue();
            assertNotNull(record.headers().lastHeader("eventType"));
            assertNotNull(record.headers().lastHeader("eventId"));
            assertNotNull(record.headers().lastHeader("requestId"));
        }

        @Test
        @DisplayName("Should mark entries as processed")
        void shouldMarkEntriesAsProcessed() {
            // Given
            UUID entryId1 = UUID.randomUUID();
            UUID entryId2 = UUID.randomUUID();
            
            List<OutboxEntry> entries = List.of(
                    new OutboxEntry(entryId1, "TWEET_CREATED", "user-1", "{}", "req-1"),
                    new OutboxEntry(entryId2, "USER_FOLLOWED", "user-2", "{}", "req-2")
            );

            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(entries);

            // When
            outboxPoller.pollAndPublish();

            // Then
            verify(outboxRepository).markAsProcessed(List.of(entryId1, entryId2));
        }

        @Test
        @DisplayName("Should increment metrics")
        void shouldIncrementMetrics() {
            // Given
            List<OutboxEntry> entries = List.of(
                    new OutboxEntry(UUID.randomUUID(), "TWEET_CREATED", "user-1", "{}", "req-1"),
                    new OutboxEntry(UUID.randomUUID(), "USER_FOLLOWED", "user-2", "{}", "req-2")
            );

            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(entries);

            // When
            outboxPoller.pollAndPublish();

            // Then
            verify(metrics).incrementOutboxEventsPublished(2);
        }

        @Test
        @DisplayName("Should process multiple entries in batch")
        void shouldProcessMultipleEntriesInBatch() {
            // Given
            List<OutboxEntry> entries = List.of(
                    new OutboxEntry(UUID.randomUUID(), "TWEET_CREATED", "user-1", "{}", null),
                    new OutboxEntry(UUID.randomUUID(), "USER_FOLLOWED", "user-2", "{}", null),
                    new OutboxEntry(UUID.randomUUID(), "USER_UNFOLLOWED", "user-3", "{}", null)
            );

            when(outboxRepository.findUnprocessedWithLock(100)).thenReturn(entries);

            // When
            outboxPoller.pollAndPublish();

            // Then
            verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
            verify(outboxRepository).markAsProcessed(argThat(ids -> ids.size() == 3));
        }
    }
}
