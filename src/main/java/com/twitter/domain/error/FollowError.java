package com.twitter.domain.error;

import com.twitter.domain.model.UserId;

/**
 * Sealed type representing expected business errors for follow operations at the application layer.
 * These errors are determined by querying state (repository), not by domain validation.
 *
 * For domain validation errors (like self-follow), see ValidationError.FollowValidationError.
 */
public sealed interface FollowError {

    record AlreadyFollowing(UserId followerId, UserId followeeId) implements FollowError {
        @Override
        public String message() {
            return "User " + followerId + " is already following " + followeeId;
        }

        @Override
        public String code() {
            return "ALREADY_FOLLOWING";
        }
    }

    record NotFollowing(UserId followerId, UserId followeeId) implements FollowError {
        @Override
        public String message() {
            return "User " + followerId + " is not following " + followeeId;
        }

        @Override
        public String code() {
            return "NOT_FOLLOWING";
        }
    }

    /**
     * Wraps a domain validation error that occurred during follow creation.
     */
    record ValidationFailed(ValidationError error) implements FollowError {
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
