package com.twitter.application.port.in;

import com.twitter.domain.model.Page;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;

public interface GetTimelineUseCase {
    Page<Tweet> getTimeline(UserId userId, String cursor, int limit);
}
