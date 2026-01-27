package com.twitter.domain.model;

import com.twitter.domain.error.ValidationError.TweetError;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TweetTest {

    private static final UserId TEST_USER_ID = UserId.random();

    @Test
    void shouldCreateTweetWithValidContent() {
        UUID id = UUID.randomUUID();
        String content = "Hello, World!";

        var result = Tweet.create(id, TEST_USER_ID, content);

        assertTrue(result.isSuccess());
        Tweet tweet = result.getOrThrow();
        assertEquals(id, tweet.id());
        assertEquals(TEST_USER_ID, tweet.userId());
        assertEquals(content, tweet.content());
        assertNotNull(tweet.createdAt());
    }

    @Test
    void shouldTrimWhitespace() {
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, "  Hello  ");
        assertTrue(result.isSuccess());
        assertEquals("Hello", result.getOrThrow().content());
    }

    @Test
    void shouldFailWithEmptyContent() {
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, "");
        assertTrue(result.isFailure());
        assertInstanceOf(TweetError.EmptyContent.class, result.errorOrNull());
    }

    @Test
    void shouldFailWithBlankContent() {
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, "   ");
        assertTrue(result.isFailure());
        assertInstanceOf(TweetError.EmptyContent.class, result.errorOrNull());
    }

    @Test
    void shouldFailWithNullContent() {
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, null);
        assertTrue(result.isFailure());
        assertInstanceOf(TweetError.EmptyContent.class, result.errorOrNull());
    }

    @Test
    void shouldFailWithContentExceeding280Characters() {
        String longContent = "a".repeat(281);
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, longContent);
        assertTrue(result.isFailure());
        assertInstanceOf(TweetError.ContentTooLong.class, result.errorOrNull());
    }

    @Test
    void shouldSucceedWithContentExactly280Characters() {
        String exactContent = "a".repeat(280);
        var result = Tweet.create(UUID.randomUUID(), TEST_USER_ID, exactContent);
        assertTrue(result.isSuccess());
        assertEquals(280, result.getOrThrow().content().length());
    }
}
