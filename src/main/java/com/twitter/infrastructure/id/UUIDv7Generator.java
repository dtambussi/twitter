package com.twitter.infrastructure.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.twitter.application.port.out.IdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UUIDv7Generator implements IdGenerator {

    private final TimeBasedEpochGenerator generator;

    public UUIDv7Generator() {
        this.generator = Generators.timeBasedEpochGenerator();
    }

    @Override
    public UUID generate() {
        return generator.generate();
    }

    @Override
    public long extractTimestamp(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
