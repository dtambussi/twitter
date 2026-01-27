package com.twitter.application.port.out;

/**
 * Admin operations for the message broker (Kafka/Redpanda).
 * Used for demo/testing purposes to reset state.
 */
public interface MessageBrokerAdmin {

    /**
     * Purges all messages from the events topic and resets consumer offsets.
     * This ensures no old messages will be reprocessed after a demo reset.
     *
     * @return number of records purged (approximate)
     */
    long purgeEventsTopic();
}
