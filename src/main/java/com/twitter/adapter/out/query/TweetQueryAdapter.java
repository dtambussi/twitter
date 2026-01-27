package com.twitter.adapter.out.query;

import com.twitter.application.port.out.TweetQueryPort;
import com.twitter.application.port.out.TweetRepository;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * In-process adapter for cross-module tweet queries.
 * In a modular monolith, this delegates to TweetRepository.
 * In microservices, this would be replaced with an HTTP/gRPC client.
 */
@Component
public class TweetQueryAdapter implements TweetQueryPort {

    private final TweetRepository tweetRepository;

    public TweetQueryAdapter(TweetRepository tweetRepository) {
        this.tweetRepository = tweetRepository;
    }

    @Override
    public List<Tweet> findByIds(List<UUID> ids) {
        return tweetRepository.findByIds(ids);
    }

    @Override
    public List<Tweet> findByUserIdLatest(UserId userId, int limit) {
        return tweetRepository.findByUserIdLatest(userId, limit);
    }
}
