package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.UserIdError;

import java.util.UUID;

/**
 * Value Object for User identity.
 * Wraps a UUID to ensure type safety and allow future refactoring without changing domain code.
 */
public record UserId(UUID value) {

    public UserId {
        // Compact constructor for internal use - assumes validated input
        if (value == null) {
            throw new IllegalStateException("UserId value cannot be null - use parse() for validation");
        }
    }

    /**
     * Parses a string into a UserId, returning a Result for expected validation failures.
     */
    public static Result<UserId, UserIdError> parse(String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(UserIdError.Empty.INSTANCE);
        }
        try {
            return Result.success(new UserId(UUID.fromString(value)));
        } catch (IllegalArgumentException e) {
            return Result.failure(new UserIdError.InvalidFormat(value));
        }
    }

    /**
     * Creates a UserId from a UUID. Since UUID is already validated, this cannot fail.
     */
    public static UserId of(UUID value) {
        return new UserId(value);
    }

    /**
     * Creates a UserId from a trusted source (e.g., database, internal messages).
     * This assumes the value is a valid UUID string - use only for data that originated from our system.
     * For external/user input, use parse() instead.
     *
     * @throws IllegalStateException if the value is not a valid UUID (indicates data corruption)
     */
    public static UserId fromTrusted(String value) {
        try {
            return new UserId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Corrupted UserId in trusted source: " + value, e);
        }
    }

    /**
     * Creates a random UserId. Useful for tests and ID generation.
     */
    public static UserId random() {
        return new UserId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
