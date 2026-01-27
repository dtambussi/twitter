package com.twitter.adapter.out.cache;

import com.twitter.application.port.out.TimelineRepository;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class RedisTimelineRepository implements TimelineRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisTimelineRepository.class);
    private static final String TIMELINE_KEY_PREFIX = "timeline:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ZSetOperations<String, String> zSetOps;
    private final AppProperties appProperties;

    public RedisTimelineRepository(RedisTemplate<String, String> redisTemplate, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.zSetOps = redisTemplate.opsForZSet();
        this.appProperties = appProperties;
    }

    @Override
    public void addTweet(UserId userId, UUID tweetId, long score) {
        String key = timelineKey(userId);
        zSetOps.add(key, tweetId.toString(), score);
        trimTimeline(userId, appProperties.getTimeline().getMaxSize());
        log.debug("Added tweet {} to timeline {}", tweetId, userId);
    }

    @Override
    public void addTweets(UserId userId, List<TweetScore> tweets) {
        if (tweets.isEmpty()) {
            return;
        }
        String key = timelineKey(userId);
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (TweetScore ts : tweets) {
            tuples.add(ZSetOperations.TypedTuple.of(ts.tweetId().toString(), (double) ts.score()));
        }
        zSetOps.add(key, tuples);
        trimTimeline(userId, appProperties.getTimeline().getMaxSize());
        log.debug("Added {} tweets to timeline {}", tweets.size(), userId);
    }

    @Override
    public void removeTweet(UserId userId, UUID tweetId) {
        String key = timelineKey(userId);
        zSetOps.remove(key, tweetId.toString());
        log.debug("Removed tweet {} from timeline {}", tweetId, userId);
    }

    @Override
    public void removeTweetsByUser(UserId timelineOwnerId, UserId tweetAuthorId, List<UUID> tweetIds) {
        if (tweetIds.isEmpty()) {
            return;
        }
        String key = timelineKey(timelineOwnerId);
        String[] values = tweetIds.stream().map(UUID::toString).toArray(String[]::new);
        zSetOps.remove(key, (Object[]) values);
        log.debug("Removed {} tweets by {} from timeline {}", tweetIds.size(), tweetAuthorId, timelineOwnerId);
    }

    @Override
    public List<UUID> getTimeline(UserId userId, Long maxScore, int limit) {
        String key = timelineKey(userId);
        Set<String> results;

        if (maxScore == null) {
            results = zSetOps.reverseRange(key, 0, limit - 1); // newest tweets
        } else { // scrolling down older tweets
            results = zSetOps.reverseRangeByScore(key, Double.NEGATIVE_INFINITY, maxScore - 1, 0, limit);
        }

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.stream()
            .map(UUID::fromString)
            .toList();
    }

    @Override
    public void trimTimeline(UserId userId, int maxSize) {
        String key = timelineKey(userId);
        // Keep only the top maxSize elements (highest scores = newest)
        zSetOps.removeRange(key, 0, -maxSize - 1);
    }

    @Override
    public long deleteAll() {
        Set<String> keys = redisTemplate.keys(TIMELINE_KEY_PREFIX + "*");
        if (keys.isEmpty()) {
            return 0;
        }
        long count = keys.size();
        redisTemplate.delete(keys);
        log.info("Deleted {} timeline keys", count);
        return count;
    }

    private String timelineKey(UserId userId) {
        return TIMELINE_KEY_PREFIX + userId.toString();
    }
}
