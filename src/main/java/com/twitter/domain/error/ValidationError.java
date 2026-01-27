package com.twitter.domain.error;

/**
 * Sealed type representing domain validation errors.
 * These are expected business outcomes, not exceptional cases.
 */
public sealed interface ValidationError {

    String message();

    String code();

    // UserId validation errors
    sealed interface UserIdError extends ValidationError {

        record Empty() implements UserIdError {
            public static final Empty INSTANCE = new Empty();
            @Override
            public String message() {
                return "User ID cannot be empty";
            }

            @Override
            public String code() {
                return "USER_ID_EMPTY";
            }
        }

        record InvalidFormat(String value) implements UserIdError {
            @Override
            public String message() {
                return "User ID must be a valid UUID format: " + value;
            }

            @Override
            public String code() {
                return "USER_ID_INVALID_FORMAT";
            }
        }
    }

    // Tweet validation errors
    sealed interface TweetError extends ValidationError {

        record EmptyContent() implements TweetError {
            public static final EmptyContent INSTANCE = new EmptyContent();
            @Override
            public String message() {
                return "Tweet content cannot be empty";
            }

            @Override
            public String code() {
                return "TWEET_CONTENT_EMPTY";
            }
        }

        record ContentTooLong(int length, int maxLength) implements TweetError {
            @Override
            public String message() {
                return "Tweet content exceeds " + maxLength + " characters (was " + length + ")";
            }

            @Override
            public String code() {
                return "TWEET_CONTENT_TOO_LONG";
            }
        }
    }

    // Follow validation errors (moved from FollowError for consistency)
    sealed interface FollowValidationError extends ValidationError {

        record SelfFollow() implements FollowValidationError {
            public static final SelfFollow INSTANCE = new SelfFollow();
            @Override
            public String message() {
                return "Cannot follow yourself";
            }

            @Override
            public String code() {
                return "SELF_FOLLOW";
            }
        }
    }
}
