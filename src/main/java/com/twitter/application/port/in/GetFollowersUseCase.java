package com.twitter.application.port.in;

import com.twitter.domain.model.Page;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;

public interface GetFollowersUseCase {
    Page<User> getFollowers(UserId userId, String cursor, int limit);
}
