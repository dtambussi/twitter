package com.twitter.adapter.in.web;

import com.twitter.application.port.in.CreateTweetUseCase;
import com.twitter.application.port.in.GetUserTweetsUseCase;
import com.twitter.application.port.out.UserRepository;
import com.twitter.domain.error.TweetError;
import com.twitter.domain.error.ValidationError;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("removal")
@WebMvcTest(TweetController.class)
class TweetControllerTest {

    private static final String TEST_USER_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CreateTweetUseCase createTweetUseCase;

    @MockBean
    private GetUserTweetsUseCase getUserTweetsUseCase;

    // Required for Spring context - used by AuthFilter
    @MockBean
    private UserRepository userRepository;

    @MockBean
    private AppProperties appProperties;

    // Required for Spring context - used by ShardContext/AuthFilter
    @MockBean
    private ShardRouter shardRouter;

    @Test
    void shouldCreateTweet() throws Exception {
        UUID tweetId = UUID.randomUUID();
        UserId userId = UserId.of(UUID.fromString(TEST_USER_UUID));
        Tweet tweet = new Tweet(tweetId, userId, "Hello!", Instant.now());

        when(createTweetUseCase.createTweet(any(UserId.class), eq("Hello!"))).thenReturn(Result.success(tweet));

        mockMvc.perform(post("/api/v1/tweets")
                .header("X-User-Id", TEST_USER_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello!\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(tweetId.toString()))
            .andExpect(jsonPath("$.userId").value(TEST_USER_UUID))
            .andExpect(jsonPath("$.content").value("Hello!"));
    }

    @Test
    void shouldRejectMissingUserId() throws Exception {
        mockMvc.perform(post("/api/v1/tweets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello!\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectEmptyContent() throws Exception {
        when(createTweetUseCase.createTweet(any(UserId.class), eq("")))
            .thenReturn(Result.failure(new TweetError.ValidationFailed(
                ValidationError.TweetError.EmptyContent.INSTANCE)));

        mockMvc.perform(post("/api/v1/tweets")
                .header("X-User-Id", TEST_USER_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("TWEET_CONTENT_EMPTY"));
    }

    @Test
    void shouldRejectInvalidUserIdFormat() throws Exception {
        mockMvc.perform(post("/api/v1/tweets")
                .header("X-User-Id", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Hello!\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetUserTweets() throws Exception {
        AppProperties.Timeline timelineProps = new AppProperties.Timeline();
        timelineProps.setDefaultPageSize(20);
        timelineProps.setMaxPageSize(100);
        when(appProperties.getTimeline()).thenReturn(timelineProps);

        UserId userId = UserId.of(UUID.fromString(TEST_USER_UUID));
        Tweet tweet = new Tweet(UUID.randomUUID(), userId, "Hello!", Instant.now());
        Page<Tweet> page = Page.of(List.of(tweet), null);

        when(getUserTweetsUseCase.getUserTweets(any(UserId.class), isNull(), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/" + TEST_USER_UUID + "/tweets")
                .header("X-User-Id", TEST_USER_UUID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].content").value("Hello!"))
            .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }
}
