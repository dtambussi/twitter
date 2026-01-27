package com.twitter.admin.adapter.out;

import com.twitter.admin.application.port.out.AdminDataPort;
import com.twitter.application.port.out.*;
import org.springframework.stereotype.Component;

/**
 * Adapter that aggregates all repository operations for admin purposes.
 * This isolates the admin module from knowing about individual repositories.
 */
@Component
public class AdminDataAdapter implements AdminDataPort {

    private final UserRepository userRepository;
    private final TweetRepository tweetRepository;
    private final FollowRepository followRepository;
    private final OutboxRepository outboxRepository;
    private final TimelineRepository timelineRepository;
    private final MessageBrokerAdmin messageBrokerAdmin;

    public AdminDataAdapter(
            UserRepository userRepository,
            TweetRepository tweetRepository,
            FollowRepository followRepository,
            OutboxRepository outboxRepository,
            TimelineRepository timelineRepository,
            MessageBrokerAdmin messageBrokerAdmin) {
        this.userRepository = userRepository;
        this.tweetRepository = tweetRepository;
        this.followRepository = followRepository;
        this.outboxRepository = outboxRepository;
        this.timelineRepository = timelineRepository;
        this.messageBrokerAdmin = messageBrokerAdmin;
    }

    @Override
    public DataCounts getCounts() {
        return new DataCounts(
            userRepository.count(),
            tweetRepository.count(),
            followRepository.count(),
            outboxRepository.countUnprocessed()
        );
    }

    @Override
    public ClearResult clearAll() {
        // Get counts before clearing
        long users = userRepository.count();
        long tweets = tweetRepository.count();
        long follows = followRepository.count();
        long outboxEvents = outboxRepository.countUnprocessed();

        // Clear Redis timelines (no FK constraints)
        long timelineKeys = timelineRepository.deleteAll();

        // Purge Kafka topic and reset consumer offsets
        long kafkaRecords = messageBrokerAdmin.purgeEventsTopic();

        // Clear PostgreSQL (order matters due to FK constraints)
        outboxRepository.deleteAll();
        followRepository.deleteAll();
        tweetRepository.deleteAll();
        userRepository.deleteAll();

        return new ClearResult(users, tweets, follows, outboxEvents, timelineKeys, kafkaRecords);
    }
}
