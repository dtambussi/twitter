package com.twitter.application.port.out;

import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TweetRepository {
    void save(Tweet tweet);
    Optional<Tweet> findById(UUID id);
    List<Tweet> findByUserId(UserId userId, UUID cursor, int limit);
    List<Tweet> findByUserIdLatest(UserId userId, int limit);
    List<Tweet> findByIds(List<UUID> ids);
    long count();
    void deleteAll();
}
