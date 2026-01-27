package com.twitter.infrastructure.metrics;

import com.twitter.application.port.out.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class AppMetrics implements MetricsPort {

    private static final Logger log = LoggerFactory.getLogger(AppMetrics.class);

    private final MeterRegistry registry;

    private Counter tweetsCreated;
    private Counter followsCreated;
    private Counter unfollowsCreated;
    private Counter timelineRequests;
    private Counter outboxEventsPublished;
    private Timer fanoutDuration;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerAllMeters();
    }

    private void registerAllMeters() {
        this.tweetsCreated = Counter.builder("tweets_created_total")
            .description("Total number of tweets created")
            .register(registry);

        this.followsCreated = Counter.builder("follows_created_total")
            .description("Total number of follow actions")
            .register(registry);

        this.unfollowsCreated = Counter.builder("unfollows_total")
            .description("Total number of unfollow actions")
            .register(registry);

        this.timelineRequests = Counter.builder("timeline_requests_total")
            .description("Total number of timeline requests")
            .register(registry);

        this.outboxEventsPublished = Counter.builder("outbox_events_published_total")
            .description("Total number of outbox events published to Kafka")
            .register(registry);

        this.fanoutDuration = Timer.builder("timeline_fanout_duration_seconds")
            .description("Time taken to fan out tweets to followers")
            .register(registry);
    }

    @Override
    public void resetAll() {
        log.info("Resetting all application metrics");

        // Remove existing meters
        registry.remove(tweetsCreated);
        registry.remove(followsCreated);
        registry.remove(unfollowsCreated);
        registry.remove(timelineRequests);
        registry.remove(outboxEventsPublished);
        registry.remove(fanoutDuration);

        // Re-register fresh meters
        registerAllMeters();

        log.info("All application metrics reset to zero");
    }

    @Override
    public void incrementTweetsCreated() {
        tweetsCreated.increment();
    }

    @Override
    public void incrementFollows() {
        followsCreated.increment();
    }

    @Override
    public void incrementUnfollows() {
        unfollowsCreated.increment();
    }

    @Override
    public void incrementTimelineRequests() {
        timelineRequests.increment();
    }

    @Override
    public void incrementOutboxEventsPublished(int count) {
        outboxEventsPublished.increment(count);
    }

    @Override
    public <T> T recordFanoutDuration(Supplier<T> operation) {
        return fanoutDuration.record(operation);
    }

    @Override
    public void recordFanoutDuration(Runnable operation) {
        fanoutDuration.record(operation);
    }
}
