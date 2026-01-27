package com.twitter.adapter.out.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twitter.application.port.out.FollowQueryPort;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.application.port.out.TimelineRepository.TweetScore;
import com.twitter.application.port.out.TweetQueryPort;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.sharding.ShardContext;
import com.twitter.infrastructure.sharding.ShardRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class TimelineEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimelineEventConsumer.class);

    private final TimelineRepository timelineRepository;
    private final FollowQueryPort followQueryPort;
    private final TweetQueryPort tweetQueryPort;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final MetricsPort metrics;
    private final AppProperties appProperties;
    private final ShardRouter shardRouter;

    public TimelineEventConsumer(
            TimelineRepository timelineRepository,
            FollowQueryPort followQueryPort,
            TweetQueryPort tweetQueryPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            MetricsPort metrics,
            AppProperties appProperties,
            ShardRouter shardRouter) {
        this.timelineRepository = timelineRepository;
        this.followQueryPort = followQueryPort;
        this.tweetQueryPort = tweetQueryPort;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.appProperties = appProperties;
        this.shardRouter = shardRouter;
    }

    @KafkaListener(topics = "${app.kafka.topic}", groupId = "twitter-timeline")
    public void consume(ConsumerRecord<String, String> record) {
        String eventType = extractHeader(record, "eventType");
        String requestId = extractHeader(record, "requestId");

        if (requestId != null) {
            MDC.put("requestId", requestId);
        }

        // Set shard context based on message key (aggregateId = userId)
        // This routes database queries to the correct shard
        if (record.key() != null) {
            UserId aggregateUserId = UserId.fromTrusted(record.key());
            int shardId = shardRouter.getShardForUser(aggregateUserId);
            ShardContext.set(shardId);
        }

        try {
            log.debug("Received event: type={}, key={}, shard={}", eventType, record.key(), ShardContext.get());

            switch (eventType) {
                case "TWEET_CREATED" -> handleTweetCreated(record.value());
                case "USER_FOLLOWED" -> handleUserFollowed(record.value());
                case "USER_UNFOLLOWED" -> handleUserUnfollowed(record.value());
                default -> log.warn("Unknown event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process event: type={}, error={}", eventType, e.getMessage(), e);
        } finally {
            MDC.remove("requestId");
            ShardContext.clear();
        }
    }

    private void handleTweetCreated(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        UserId userId = UserId.fromTrusted(json.get("userId").get("value").asText());
        UUID tweetId = UUID.fromString(json.get("tweetId").asText());
        long score = idGenerator.extractTimestamp(tweetId);

        // Check if user is a celebrity (fan-out on read instead)
        long followerCount = followQueryPort.countFollowers(userId);
        int threshold = appProperties.getTimeline().getCelebrityFollowerThreshold();

        if (followerCount > threshold) {
            log.debug("Skipping fan-out for celebrity {} with {} followers (threshold={}), tweet {}",
                userId, followerCount, threshold, tweetId);
            return;
        }

        metrics.recordFanoutDuration(() -> {
            List<UserId> followerIds = followQueryPort.findAllFollowerIds(userId);
            log.debug("Fan-out tweet {} to {} followers", tweetId, followerIds.size());

            for (UserId followerId : followerIds) {
                timelineRepository.addTweet(followerId, tweetId, score);
            }
        });
    }

    private void handleUserFollowed(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        UserId followerId = UserId.fromTrusted(json.get("followerId").get("value").asText());
        UserId followeeId = UserId.fromTrusted(json.get("followeeId").get("value").asText());

        // Backfill: Add recent tweets from followee to follower's timeline
        List<Tweet> recentTweets = tweetQueryPort.findByUserIdLatest(
            followeeId,
            appProperties.getTimeline().getMaxSize()
        );

        if (!recentTweets.isEmpty()) {
            List<TweetScore> tweetScores = recentTweets.stream()
                .map(t -> new TweetScore(t.id(), idGenerator.extractTimestamp(t.id())))
                .toList();
            timelineRepository.addTweets(followerId, tweetScores);
            log.debug("Backfilled {} tweets from {} to {}'s timeline", recentTweets.size(), followeeId, followerId);
        }
    }

    private void handleUserUnfollowed(String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        UserId followerId = UserId.fromTrusted(json.get("followerId").get("value").asText());
        UserId followeeId = UserId.fromTrusted(json.get("followeeId").get("value").asText());

        // Remove all tweets from unfollowed user from timeline
        List<Tweet> tweets = tweetQueryPort.findByUserIdLatest(
            followeeId,
            appProperties.getTimeline().getMaxSize()
        );

        if (!tweets.isEmpty()) {
            List<UUID> tweetIds = tweets.stream().map(Tweet::id).toList();
            timelineRepository.removeTweetsByUser(followerId, followeeId, tweetIds);
            log.debug("Removed {} tweets from {} from {}'s timeline", tweets.size(), followeeId, followerId);
        }
    }

    private String extractHeader(ConsumerRecord<String, String> record, String headerName) {
        var header = record.headers().lastHeader(headerName);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
