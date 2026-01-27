package com.twitter.adapter.out.persistence;

import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.integration.base.FullStackTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIf("isDockerAvailable")
class JdbcUserRepositoryTest extends FullStackTestBase {

    @Autowired
    private JdbcUserRepository repository;

    @Test
    void shouldSaveAndFindUser() {
        // Given
        UserId userId = UserId.random();
        User user = User.create(userId);

        // When
        repository.upsert(user);
        Optional<User> found = repository.findById(userId);

        // Then
        assertTrue(found.isPresent());
        assertEquals(userId, found.get().id());
        assertNotNull(found.get().createdAt());
    }

    @Test
    void shouldReturnEmptyWhenUserNotFound() {
        // Given
        UserId userId = UserId.random();

        // When
        Optional<User> found = repository.findById(userId);

        // Then
        assertTrue(found.isEmpty());
    }

    @Test
    void shouldCheckIfUserExists() {
        // Given
        UserId userId = UserId.random();
        User user = User.create(userId);

        // When
        assertFalse(repository.exists(userId));
        repository.upsert(user);

        // Then
        assertTrue(repository.exists(userId));
    }

    @Test
    void shouldUpsertUserWithoutError() {
        // Given
        UserId userId = UserId.random();
        User user = User.create(userId);

        // When/Then - should not throw
        repository.upsert(user);
        repository.upsert(user); // Upsert again should not fail
        assertTrue(repository.exists(userId));
    }

    @Test
    void shouldCountUsers() {
        // Given
        assertEquals(0, repository.count());

        // When
        repository.upsert(User.create(UserId.random()));
        repository.upsert(User.create(UserId.random()));
        repository.upsert(User.create(UserId.random()));

        // Then
        assertEquals(3, repository.count());
    }

    @Test
    void shouldDeleteAllUsers() {
        // Given
        repository.upsert(User.create(UserId.random()));
        repository.upsert(User.create(UserId.random()));
        assertEquals(2, repository.count());

        // When
        repository.deleteAll();

        // Then
        assertEquals(0, repository.count());
    }
}
