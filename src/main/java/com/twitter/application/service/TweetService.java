package com.twitter.application.service;

import com.twitter.application.port.in.CreateTweetUseCase;
import com.twitter.application.port.in.GetUserTweetsUseCase;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.OutboxRepository;
import com.twitter.application.port.out.TweetRepository;
import com.twitter.domain.error.TweetError;
import com.twitter.domain.event.TweetCreated;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class TweetService implements CreateTweetUseCase, GetUserTweetsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TweetService.class);

    private final TweetRepository tweetRepository;
    private final OutboxRepository outboxRepository;
    private final IdGenerator idGenerator;
    private final MetricsPort metrics;

    public TweetService(
            TweetRepository tweetRepository,
            OutboxRepository outboxRepository,
            IdGenerator idGenerator,
            MetricsPort metrics) {
        this.tweetRepository = tweetRepository;
        this.outboxRepository = outboxRepository;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public Result<Tweet, TweetError> createTweet(UserId userId, String content) {
        log.debug("Creating tweet for user={}, contentLength={}", userId, content != null ? content.length() : 0);
        
        UUID tweetId = idGenerator.generate();

        // Domain validation via Tweet.create()
        var tweetResult = Tweet.create(tweetId, userId, content);
        if (tweetResult.isFailure()) {
            log.warn("Tweet validation failed for user={}: {}", userId, tweetResult.errorOrNull().message());
            return Result.failure(new TweetError.ValidationFailed(tweetResult.errorOrNull()));
        }

        Tweet tweet = tweetResult.getOrThrow();
        tweetRepository.save(tweet);
        log.debug("Tweet saved to database: tweetId={}", tweetId);

        TweetCreated event = TweetCreated.from(
            idGenerator.generate(),
            tweetId,
            userId,
            tweet.content() // Use trimmed content from tweet
        );
        outboxRepository.save(event, RequestContext.getRequestId());
        log.debug("TweetCreated event queued in outbox: eventId={}", event.eventId());

        metrics.incrementTweetsCreated();
        log.info("Tweet created successfully: tweetId={}, userId={}, chars={}", tweetId, userId, tweet.content().length());

        return Result.success(tweet);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Tweet> getUserTweets(UserId userId, String cursor, int limit) {
        log.debug("Fetching tweets for user={}, cursor={}, limit={}", userId, cursor != null ? "present" : "none", limit);
        
        UUID cursorId = decodeCursor(cursor);
        List<Tweet> tweets = tweetRepository.findByUserId(userId, cursorId, limit + 1);

        boolean hasMore = tweets.size() > limit;
        if (hasMore) {
            tweets = tweets.subList(0, limit);
        }

        String nextCursor = hasMore ? encodeCursor(tweets.get(tweets.size() - 1).id()) : null;
        log.debug("Returning {} tweets for user={}, hasMore={}", tweets.size(), userId, hasMore);
        
        return Page.of(tweets, nextCursor);
    }

    private UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            return UUID.fromString(decoded);
        } catch (Exception e) {
            log.warn("Invalid cursor: {}", cursor);
            return null;
        }
    }

    private String encodeCursor(UUID id) {
        return Base64.getEncoder().encodeToString(id.toString().getBytes());
    }
}
