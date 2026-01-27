package com.twitter.application.port.out;

import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;

import java.util.Optional;

public interface UserRepository {
    void upsert(User user);
    Optional<User> findById(UserId id);
    boolean exists(UserId id);
    long count();
    void deleteAll();
}
