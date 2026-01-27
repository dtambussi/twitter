package com.twitter.application.service;

import com.twitter.application.port.in.GetTimelineUseCase;
import com.twitter.application.port.out.FollowQueryPort;
import com.twitter.application.port.out.IdGenerator;
import com.twitter.application.port.out.MetricsPort;
import com.twitter.application.port.out.TimelineRepository;
import com.twitter.application.port.out.TweetQueryPort;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Stream;

@Service
public class TimelineService implements GetTimelineUseCase {

    private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

    private final TimelineRepository timelineRepository;
    private final TweetQueryPort tweetQueryPort;
    private final FollowQueryPort followQueryPort;
    private final IdGenerator idGenerator;
    private final AppProperties appProperties;
    private final MetricsPort metrics;

    public TimelineService(
            TimelineRepository timelineRepository,
            TweetQueryPort tweetQueryPort,
            FollowQueryPort followQueryPort,
            IdGenerator idGenerator,
            AppProperties appProperties,
            MetricsPort metrics) {
        this.timelineRepository = timelineRepository;
        this.tweetQueryPort = tweetQueryPort;
        this.followQueryPort = followQueryPort;
        this.idGenerator = idGenerator;
        this.appProperties = appProperties;
        this.metrics = metrics;
    }

    @Override
    public Page<Tweet> getTimeline(UserId userId, String cursor, int limit) {
        log.debug("Fetching timeline for user={}, cursor={}, limit={}", userId, cursor != null ? "present" : "none", limit);
        
        Long maxScore = decodeCursorToScore(cursor);

        // Get pre-materialized timeline from cache (fan-out on write tweets)
        List<UUID> cachedTweetIds = timelineRepository.getTimeline(userId, maxScore, limit + 1);
        log.debug("Retrieved {} cached tweet IDs from Redis", cachedTweetIds.size());

        // Fan-out on read: fetch celebrity tweets
        List<Tweet> celebrityTweets = fetchCelebrityTweets(userId, maxScore, limit);

        // Merge cached tweet IDs with celebrity tweets
        List<Tweet> allTweets = mergeTweets(cachedTweetIds, celebrityTweets, limit + 1);

        if (allTweets.isEmpty()) {
            log.debug("Timeline empty for user={}", userId);
            metrics.incrementTimelineRequests();
            return Page.empty();
        }

        boolean hasMore = allTweets.size() > limit;
        if (hasMore) {
            allTweets = allTweets.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !allTweets.isEmpty()) {
            Tweet lastTweet = allTweets.get(allTweets.size() - 1);
            nextCursor = encodeCursor(lastTweet.id());
        }

        metrics.incrementTimelineRequests();
        log.info("Timeline served: user={}, tweets={} (cached={}, celebrity={}), hasMore={}",
            userId, allTweets.size(), cachedTweetIds.size(), celebrityTweets.size(), hasMore);

        return Page.of(allTweets, nextCursor);
    }

    /**
     * Fetch recent tweets from followed celebrities (fan-out on read).
     */
    private List<Tweet> fetchCelebrityTweets(UserId userId, Long maxScore, int limit) {
        int threshold = appProperties.getTimeline().getCelebrityFollowerThreshold();
        List<UserId> celebrities = followQueryPort.findFollowedCelebrities(userId, threshold);

        if (celebrities.isEmpty()) {
            return List.of();
        }

        log.debug("Fetching tweets from {} followed celebrities for user {}", celebrities.size(), userId);

        // Fetch recent tweets from each celebrity
        List<Tweet> celebrityTweets = new ArrayList<>();
        for (UserId celebrityId : celebrities) {
            List<Tweet> tweets = tweetQueryPort.findByUserIdLatest(celebrityId, limit);
            // Filter by cursor if present
            if (maxScore != null) {
                tweets = tweets.stream()
                    .filter(t -> idGenerator.extractTimestamp(t.id()) < maxScore)
                    .toList();
            }
            celebrityTweets.addAll(tweets);
        }

        return celebrityTweets;
    }

    /**
     * Merge cached tweet IDs with celebrity tweets, sorted by time descending.
     */
    private List<Tweet> mergeTweets(List<UUID> cachedTweetIds, List<Tweet> celebrityTweets, int limit) {
        // Fetch full tweets for cached IDs
        List<Tweet> cachedTweets = cachedTweetIds.isEmpty()
            ? List.of()
            : tweetQueryPort.findByIds(cachedTweetIds);

        // Merge and deduplicate
        Set<UUID> seenIds = new HashSet<>();
        List<Tweet> merged = Stream.concat(cachedTweets.stream(), celebrityTweets.stream())
            .filter(t -> seenIds.add(t.id())) // deduplicate
            .sorted(Comparator.comparing(Tweet::id).reversed()) // newest first (UUIDv7)
            .limit(limit)
            .toList();

        return new ArrayList<>(merged);
    }

    private Long decodeCursorToScore(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            UUID uuid = UUID.fromString(decoded);
            return idGenerator.extractTimestamp(uuid);
        } catch (Exception e) {
            log.warn("Invalid cursor: {}", cursor);
            return null;
        }
    }

    private String encodeCursor(UUID id) {
        return Base64.getEncoder().encodeToString(id.toString().getBytes());
    }
}
