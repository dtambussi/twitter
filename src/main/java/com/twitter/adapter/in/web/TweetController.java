package com.twitter.adapter.in.web;

import com.twitter.application.port.in.CreateTweetUseCase;
import com.twitter.application.port.in.GetUserTweetsUseCase;
import com.twitter.domain.error.TweetError;
import com.twitter.domain.error.ValidationError;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.context.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tweets", description = "Tweet operations")
public class TweetController {

    private final CreateTweetUseCase createTweetUseCase;
    private final GetUserTweetsUseCase getUserTweetsUseCase;
    private final AppProperties appProperties;

    public TweetController(
            CreateTweetUseCase createTweetUseCase,
            GetUserTweetsUseCase getUserTweetsUseCase,
            AppProperties appProperties) {
        this.createTweetUseCase = createTweetUseCase;
        this.getUserTweetsUseCase = getUserTweetsUseCase;
        this.appProperties = appProperties;
    }

    @PostMapping("/tweets")
    @Operation(summary = "Create a new tweet", description = "Creates a tweet for the authenticated user (max 280 characters)")
    public ResponseEntity<?> createTweet(
        @Parameter(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @RequestHeader("X-User-Id") String userIdStr,
        @Valid @RequestBody CreateTweetRequest request
    ) {
        var userIdResult = UserId.parse(userIdStr);
        if (userIdResult.isFailure()) {
            return toValidationErrorResponse(userIdResult.errorOrNull());
        }
        
        Result<Tweet, TweetError> result = createTweetUseCase.createTweet(userIdResult.getOrThrow(), request.content());

        return result.isSuccess()
            ? ResponseEntity.status(HttpStatus.CREATED).body(TweetResponse.from(result.getOrThrow()))
            : toErrorResponse(result.errorOrNull());
    }

    @GetMapping("/users/{userId}/tweets")
    @Operation(summary = "Get user's tweets", description = "Returns paginated list of tweets by the specified user")
    public ResponseEntity<?> getUserTweets(
            @Parameter(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String userId,
            @Parameter(description = "Pagination cursor from previous response")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of tweets to return (max 100)")
            @RequestParam(required = false) Integer limit) {

        var userIdResult = UserId.parse(userId);
        if (userIdResult.isFailure()) {
            return toValidationErrorResponse(userIdResult.errorOrNull());
        }

        int effectiveLimit = limit != null
            ? Math.min(limit, appProperties.getTimeline().getMaxPageSize())
            : appProperties.getTimeline().getDefaultPageSize();

        Page<Tweet> page = getUserTweetsUseCase.getUserTweets(userIdResult.getOrThrow(), cursor, effectiveLimit);
        return ResponseEntity.ok(PageResponse.from(page, TweetResponse::from));
    }

    private ResponseEntity<ErrorResponse> toErrorResponse(TweetError error) {
        String requestId = RequestContext.getRequestId();
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(error.code(), error.message(), requestId));
    }

    private ResponseEntity<ErrorResponse> toValidationErrorResponse(ValidationError error) {
        String requestId = RequestContext.getRequestId();
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(error.code(), error.message(), requestId));
    }

    public record CreateTweetRequest(String content) {}

    public record ErrorResponse(String error, String message, String requestId) {}

    public record TweetResponse(
        UUID id,
        String userId,
        String content,
        Instant createdAt
    ) {
        public static TweetResponse from(Tweet tweet) {
            return new TweetResponse(tweet.id(), tweet.userId().toString(), tweet.content(), tweet.createdAt());
        }
    }
}
