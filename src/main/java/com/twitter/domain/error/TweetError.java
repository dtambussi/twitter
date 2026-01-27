package com.twitter.domain.error;

/**
 * Sealed type representing expected business errors for tweet operations at the application layer.
 */
public sealed interface TweetError {

    /**
     * Wraps a domain validation error that occurred during tweet creation.
     */
    record ValidationFailed(ValidationError error) implements TweetError {
        @Override
        public String message() {
            return error.message();
        }

        @Override
        public String code() {
            return error.code();
        }
    }

    String message();

    String code();
}
