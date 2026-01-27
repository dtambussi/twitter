package com.twitter.admin.application.port.out;

/**
 * Port for admin operations across all data stores.
 * Aggregates cross-cutting admin operations to avoid coupling
 * the admin module to individual repositories.
 */
public interface AdminDataPort {

    /**
     * Returns counts of all entities in the system.
     */
    DataCounts getCounts();

    /**
     * Clears all data from all stores.
     * Returns counts of what was cleared.
     */
    ClearResult clearAll();

    record DataCounts(
        long users,
        long tweets,
        long follows,
        long pendingOutboxEvents
    ) {}

    record ClearResult(
        long users,
        long tweets,
        long follows,
        long outboxEvents,
        long timelineKeys,
        long kafkaRecordsPurged
    ) {}
}
