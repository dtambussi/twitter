package com.twitter.application.port.out;

import com.twitter.domain.event.DomainEvent;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
    void save(DomainEvent event, String requestId);
    List<OutboxEntry> findUnprocessedWithLock(int limit);
    void markAsProcessed(List<UUID> ids);
    void deleteProcessedOlderThan(java.time.Instant threshold);
    long countUnprocessed();
    void deleteAll();

    record OutboxEntry(
        UUID id,
        String eventType,
        String aggregateId,
        String payload,
        String requestId
    ) {}
}
