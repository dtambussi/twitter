package com.twitter.application.port.out;

import java.util.function.Supplier;

/**
 * Port for recording application metrics.
 * Abstracts the metrics infrastructure from application services.
 */
public interface MetricsPort {

    void incrementTweetsCreated();

    void incrementFollows();

    void incrementUnfollows();

    void incrementTimelineRequests();

    void incrementOutboxEventsPublished(int count);

    <T> T recordFanoutDuration(Supplier<T> operation);

    void recordFanoutDuration(Runnable operation);

    /**
     * Resets all metrics to zero. Used for demo/testing purposes.
     */
    void resetAll();
}
