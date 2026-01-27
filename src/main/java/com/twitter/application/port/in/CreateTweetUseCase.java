package com.twitter.application.port.in;

import com.twitter.domain.error.TweetError;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;

public interface CreateTweetUseCase {
    Result<Tweet, TweetError> createTweet(UserId userId, String content);
}
