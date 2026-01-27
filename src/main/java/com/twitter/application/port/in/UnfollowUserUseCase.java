package com.twitter.application.port.in;

import com.twitter.domain.error.FollowError;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.UserId;

public interface UnfollowUserUseCase {
    Result<Void, FollowError> unfollow(UserId followerId, UserId followeeId);
}
