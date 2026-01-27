package com.twitter.application.port.out;

import com.twitter.domain.model.UserId;

import java.util.List;
import java.util.UUID;

public interface TimelineRepository {
    void addTweet(UserId userId, UUID tweetId, long score);
    void addTweets(UserId userId, List<TweetScore> tweets);
    void removeTweet(UserId userId, UUID tweetId);
    void removeTweetsByUser(UserId timelineOwnerId, UserId tweetAuthorId, List<UUID> tweetIds);
    List<UUID> getTimeline(UserId userId, Long maxScore, int limit);
    void trimTimeline(UserId userId, int maxSize);
    long deleteAll();

    record TweetScore(UUID tweetId, long score) {}
}
