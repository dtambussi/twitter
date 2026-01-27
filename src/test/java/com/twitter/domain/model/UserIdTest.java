package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.UserIdError;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserIdTest {

    private static final String VALID_UUID_1 = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_UUID_2 = "550e8400-e29b-41d4-a716-446655440001";

    @Test
    void parseShouldSucceedWithValidUUID() {
        var result = UserId.parse(VALID_UUID_1);
        assertTrue(result.isSuccess());
        assertEquals(UUID.fromString(VALID_UUID_1), result.getOrThrow().value());
    }

    @Test
    void shouldCreateFromUUID() {
        UUID uuid = UUID.randomUUID();
        UserId userId = UserId.of(uuid);
        assertEquals(uuid, userId.value());
    }

    @Test
    void shouldCreateRandomUserId() {
        UserId userId = UserId.random();
        assertNotNull(userId.value());
    }

    @Test
    void parseShouldFailWithNullValue() {
        var result = UserId.parse(null);
        assertTrue(result.isFailure());
        assertInstanceOf(UserIdError.Empty.class, result.errorOrNull());
    }

    @Test
    void parseShouldFailWithEmptyValue() {
        var result = UserId.parse("");
        assertTrue(result.isFailure());
        assertInstanceOf(UserIdError.Empty.class, result.errorOrNull());
    }

    @Test
    void parseShouldFailWithBlankValue() {
        var result = UserId.parse("   ");
        assertTrue(result.isFailure());
        assertInstanceOf(UserIdError.Empty.class, result.errorOrNull());
    }

    @Test
    void parseShouldFailWithInvalidUUIDFormat() {
        var result = UserId.parse("not-a-uuid");
        assertTrue(result.isFailure());
        assertInstanceOf(UserIdError.InvalidFormat.class, result.errorOrNull());
    }

    @Test
    void parseShouldFailWithArbitraryStrings() {
        var result = UserId.parse("alice");
        assertTrue(result.isFailure());
        assertInstanceOf(UserIdError.InvalidFormat.class, result.errorOrNull());
    }

    @Test
    void fromTrustedShouldSucceedWithValidUUID() {
        UserId userId = UserId.fromTrusted(VALID_UUID_1);
        assertEquals(UUID.fromString(VALID_UUID_1), userId.value());
    }

    @Test
    void fromTrustedShouldThrowOnInvalidData() {
        assertThrows(IllegalStateException.class, () -> UserId.fromTrusted("corrupted-data"));
    }

    @Test
    void shouldBeEqualForSameValue() {
        var result1 = UserId.parse(VALID_UUID_1);
        var result2 = UserId.parse(VALID_UUID_1);
        assertEquals(result1.getOrThrow(), result2.getOrThrow());
        assertEquals(result1.getOrThrow().hashCode(), result2.getOrThrow().hashCode());
    }

    @Test
    void shouldNotBeEqualForDifferentValue() {
        var result1 = UserId.parse(VALID_UUID_1);
        var result2 = UserId.parse(VALID_UUID_2);
        assertNotEquals(result1.getOrThrow(), result2.getOrThrow());
    }

    @Test
    void toStringShouldReturnUUIDString() {
        var result = UserId.parse(VALID_UUID_1);
        assertEquals(VALID_UUID_1, result.getOrThrow().toString());
    }
}
