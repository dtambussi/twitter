package com.twitter.infrastructure.exception;

public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(String userId) {
        super("USER_NOT_FOUND", "User not found: " + userId);
    }
}
