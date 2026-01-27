package com.twitter.adapter.out.messaging;

import com.twitter.application.port.out.MessageBrokerAdmin;
import com.twitter.infrastructure.config.AppProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaAdminAdapter implements MessageBrokerAdmin {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminAdapter.class);
    private static final String CONSUMER_GROUP = "twitter-timeline";
    private static final long TIMEOUT_SECONDS = 30;

    private final KafkaAdmin kafkaAdmin;
    private final AppProperties appProperties;

    public KafkaAdminAdapter(KafkaAdmin kafkaAdmin, AppProperties appProperties) {
        this.kafkaAdmin = kafkaAdmin;
        this.appProperties = appProperties;
    }

    @Override
    public long purgeEventsTopic() {
        String topic = appProperties.getKafka().getTopic();
        log.info("Purging Kafka topic: {}", topic);

        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            // Get topic partitions
            Set<TopicPartition> partitions = getTopicPartitions(adminClient, topic);
            if (partitions.isEmpty()) {
                log.warn("Topic {} not found or has no partitions", topic);
                return 0;
            }

            // Get end offsets (latest position)
            Map<TopicPartition, Long> endOffsets = adminClient.listOffsets(
                partitions.stream().collect(
                    java.util.stream.Collectors.toMap(
                        tp -> tp,
                        tp -> org.apache.kafka.clients.admin.OffsetSpec.latest()
                    )
                )
            ).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().offset()
                ));

            long totalRecords = endOffsets.values().stream().mapToLong(Long::longValue).sum();

            // Delete all records up to end offset
            Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                recordsToDelete.put(entry.getKey(), RecordsToDelete.beforeOffset(entry.getValue()));
            }

            adminClient.deleteRecords(recordsToDelete)
                .all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Reset consumer group offsets to end
            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                offsetsToCommit.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
            }

            try {
                adminClient.alterConsumerGroupOffsets(CONSUMER_GROUP, offsetsToCommit)
                    .all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Reset consumer group {} offsets to end", CONSUMER_GROUP);
            } catch (ExecutionException e) {
                // Consumer group might not exist yet, that's ok
                log.debug("Could not reset consumer group offsets (group may not exist): {}", e.getMessage());
            }

            log.info("Purged {} records from topic {}", totalRecords, topic);
            return totalRecords;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while purging topic", e);
            return 0;
        } catch (ExecutionException | TimeoutException e) {
            log.error("Failed to purge topic: {}", e.getMessage(), e);
            return 0;
        }
    }

    private Set<TopicPartition> getTopicPartitions(AdminClient adminClient, String topic)
            throws ExecutionException, InterruptedException, TimeoutException {
        return adminClient.describeTopics(Collections.singletonList(topic))
            .allTopicNames()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .get(topic)
            .partitions()
            .stream()
            .map(info -> new TopicPartition(topic, info.partition()))
            .collect(java.util.stream.Collectors.toSet());
    }
}
