package com.twitter.application.port.in;

import com.twitter.domain.model.Page;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;

public interface GetFollowingUseCase {
    Page<User> getFollowing(UserId userId, String cursor, int limit);
}
