package com.twitter.adapter.out.messaging;

import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.OutboxRepository.OutboxEntry;
import com.twitter.infrastructure.config.AppProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties appProperties;
    private final MetricsPort metrics;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            AppProperties appProperties,
            MetricsPort metrics) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.appProperties = appProperties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEntry> entries = outboxRepository.findUnprocessedWithLock(
            appProperties.getOutbox().getBatchSize()
        );

        if (entries.isEmpty()) {
            return;
        }

        log.debug("Processing {} outbox entries", entries.size());

        for (OutboxEntry entry : entries) {
            publishToKafka(entry);
        }

        List<UUID> processedIds = entries.stream()
            .map(OutboxEntry::id)
            .toList();
        outboxRepository.markAsProcessed(processedIds);

        metrics.incrementOutboxEventsPublished(entries.size());
        log.info("Published {} events to Kafka", entries.size());
    }

    private void publishToKafka(OutboxEntry entry) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            appProperties.getKafka().getTopic(),
            null,
            entry.aggregateId(),
            entry.payload()
        );

        // Add headers for tracing
        record.headers().add(new RecordHeader("eventType", entry.eventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("eventId", entry.id().toString().getBytes(StandardCharsets.UTF_8)));
        if (entry.requestId() != null) {
            record.headers().add(new RecordHeader("requestId", entry.requestId().getBytes(StandardCharsets.UTF_8)));
        }

        kafkaTemplate.send(record);
        log.debug("Published event: type={}, aggregateId={}", entry.eventType(), entry.aggregateId());
    }

    @Scheduled(cron = "0 0 * * * *") // Every hour
    @Transactional
    public void cleanupOldEvents() {
        Instant threshold = Instant.now().minus(24, ChronoUnit.HOURS);
        outboxRepository.deleteProcessedOlderThan(threshold);
        log.info("Cleaned up processed outbox events older than 24 hours");
    }
}
