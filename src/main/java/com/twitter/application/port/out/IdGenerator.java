package com.twitter.application.port.out;

import java.util.UUID;

/**
 * Port for generating unique identifiers.
 * Abstracts ID generation strategy from application services.
 */
public interface IdGenerator {

    /**
     * Generates a new unique identifier.
     * Implementations should ensure time-ordering (e.g., UUIDv7).
     */
    UUID generate();

    /**
     * Extracts the timestamp from a time-based UUID.
     * Returns milliseconds since epoch.
     */
    long extractTimestamp(UUID id);
}
