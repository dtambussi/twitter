package com.twitter.infrastructure.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UUIDv7GeneratorTest {

    private final UUIDv7Generator generator = new UUIDv7Generator();

    @Test
    void shouldGenerateUniqueIds() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(generator.generate());
        }
        assertEquals(1000, ids.size(), "All generated UUIDs should be unique");
    }

    @Test
    void shouldBeTimeSorted() {
        UUID id1 = generator.generate();
        UUID id2 = generator.generate();
        UUID id3 = generator.generate();

        assertTrue(id1.compareTo(id2) < 0, "IDs should be time-sorted");
        assertTrue(id2.compareTo(id3) < 0, "IDs should be time-sorted");
    }

    @Test
    void shouldExtractTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = generator.generate();
        long after = System.currentTimeMillis();

        long timestamp = generator.extractTimestamp(id);

        assertTrue(timestamp >= before, "Timestamp should be >= test start time");
        assertTrue(timestamp <= after, "Timestamp should be <= test end time");
    }
}
