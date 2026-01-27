package com.twitter.adapter.in.web;

import com.twitter.application.port.in.FollowUserUseCase;
import com.twitter.application.port.in.GetFollowersUseCase;
import com.twitter.application.port.in.GetFollowingUseCase;
import com.twitter.application.port.in.UnfollowUserUseCase;
import com.twitter.domain.error.FollowError;
import com.twitter.domain.error.ValidationError;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Result;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.context.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Follows", description = "Follow/unfollow operations")
public class FollowController {

    private final FollowUserUseCase followUserUseCase;
    private final UnfollowUserUseCase unfollowUserUseCase;
    private final GetFollowingUseCase getFollowingUseCase;
    private final GetFollowersUseCase getFollowersUseCase;
    private final AppProperties appProperties;

    public FollowController(
            FollowUserUseCase followUserUseCase,
            UnfollowUserUseCase unfollowUserUseCase,
            GetFollowingUseCase getFollowingUseCase,
            GetFollowersUseCase getFollowersUseCase,
            AppProperties appProperties) {
        this.followUserUseCase = followUserUseCase;
        this.unfollowUserUseCase = unfollowUserUseCase;
        this.getFollowingUseCase = getFollowingUseCase;
        this.getFollowersUseCase = getFollowersUseCase;
        this.appProperties = appProperties;
    }

    @PostMapping("/users/{userId}/follow/{targetId}")
    @Operation(summary = "Follow a user", description = "Make the current user follow the target user")
    public ResponseEntity<?> followUser(
            @Parameter(description = "User ID (follower)", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String userId,
            @Parameter(description = "Target User ID (to follow)", example = "660e8400-e29b-41d4-a716-446655440001")
            @PathVariable String targetId) {

        var authError = validateUserIdMatchesAuth(userId);
        if (authError != null) return authError;

        var followerResult = UserId.parse(userId);
        if (followerResult.isFailure()) {
            return toValidationErrorResponse(followerResult.errorOrNull());
        }

        var followeeResult = UserId.parse(targetId);
        if (followeeResult.isFailure()) {
            return toValidationErrorResponse(followeeResult.errorOrNull());
        }

        Result<Void, FollowError> result = followUserUseCase.followUser(
            followerResult.getOrThrow(),
            followeeResult.getOrThrow()
        );

        return result.isSuccess()
            ? ResponseEntity.status(HttpStatus.CREATED)
                .body(new FollowResponse(userId, targetId, "followed"))
            : toFollowErrorResponse(result.errorOrNull());
    }

    @DeleteMapping("/users/{userId}/follow/{targetId}")
    @Operation(summary = "Unfollow a user", description = "Make the current user unfollow the target user")
    public ResponseEntity<?> unfollowUser(
            @Parameter(description = "User ID (follower)", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String userId,
            @Parameter(description = "Target User ID (to unfollow)", example = "660e8400-e29b-41d4-a716-446655440001")
            @PathVariable String targetId) {

        var authError = validateUserIdMatchesAuth(userId);
        if (authError != null) return authError;

        var followerResult = UserId.parse(userId);
        if (followerResult.isFailure()) {
            return toValidationErrorResponse(followerResult.errorOrNull());
        }

        var followeeResult = UserId.parse(targetId);
        if (followeeResult.isFailure()) {
            return toValidationErrorResponse(followeeResult.errorOrNull());
        }

        Result<Void, FollowError> result = unfollowUserUseCase.unfollow(
            followerResult.getOrThrow(),
            followeeResult.getOrThrow()
        );

        return result.isSuccess()
            ? ResponseEntity.ok(new FollowResponse(userId, targetId, "unfollowed"))
            : toFollowErrorResponse(result.errorOrNull());
    }

    @GetMapping("/users/{userId}/following")
    @Operation(summary = "Get following list", description = "Returns paginated list of users that the specified user follows")
    public ResponseEntity<?> getFollowing(
        @Parameter(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @RequestHeader("X-User-Id") String userIdStr,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        var userIdResult = UserId.parse(userIdStr);
        if (userIdResult.isFailure()) {
            return toValidationErrorResponse(userIdResult.errorOrNull());
        }
        Page<User> page = getFollowingUseCase.getFollowing(userIdResult.getOrThrow(), cursor, limit);
        return ResponseEntity.ok(PageResponse.from(page, UserResponse::from));
    }

    @GetMapping("/users/{userId}/followers")
    @Operation(summary = "Get followers list", description = "Returns paginated list of users following the specified user")
    public ResponseEntity<?> getFollowers(
            @Parameter(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String userId,
            @Parameter(description = "Pagination cursor from previous response")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of users to return (max 100)")
            @RequestParam(required = false) Integer limit) {

        var userIdResult = UserId.parse(userId);
        if (userIdResult.isFailure()) {
            return toValidationErrorResponse(userIdResult.errorOrNull());
        }

        int effectiveLimit = limit != null
            ? Math.min(limit, appProperties.getTimeline().getMaxPageSize())
            : appProperties.getTimeline().getDefaultPageSize();

        Page<User> page = getFollowersUseCase.getFollowers(userIdResult.getOrThrow(), cursor, effectiveLimit);
        return ResponseEntity.ok(PageResponse.from(page, UserResponse::from));
    }

    private ResponseEntity<ErrorResponse> validateUserIdMatchesAuth(String userId) {
        UserId authUserId = RequestContext.getUserId();
        if (!userId.equals(authUserId.toString())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "User ID in path must match authenticated user", RequestContext.getRequestId()));
        }
        return null;
    }

    private ResponseEntity<ErrorResponse> toFollowErrorResponse(FollowError error) {
        String requestId = RequestContext.getRequestId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse(error.code(), error.message(), requestId));
    }

    private ResponseEntity<ErrorResponse> toValidationErrorResponse(ValidationError error) {
        String requestId = RequestContext.getRequestId();
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(error.code(), error.message(), requestId));
    }

    public record ErrorResponse(String error, String message, String requestId) {}

    public record FollowResponse(
        String followerId,
        String followeeId,
        String status
    ) {}

    public record UserResponse(
        String id,
        Instant createdAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(user.id().toString(), user.createdAt());
        }
    }
}
