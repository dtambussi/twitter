package com.twitter.adapter.in.web;

import com.twitter.application.port.in.GetTimelineUseCase;
import com.twitter.domain.error.ValidationError;
import com.twitter.domain.model.Page;
import com.twitter.domain.model.Tweet;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.config.AppProperties;
import com.twitter.infrastructure.context.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Timeline", description = "Timeline operations")
public class TimelineController {

    private final GetTimelineUseCase getTimelineUseCase;
    private final AppProperties appProperties;

    public TimelineController(GetTimelineUseCase getTimelineUseCase, AppProperties appProperties) {
        this.getTimelineUseCase = getTimelineUseCase;
        this.appProperties = appProperties;
    }

    @GetMapping("/users/{userId}/timeline")
    @Operation(summary = "Get home timeline", description = "Returns paginated timeline of tweets from followed users")
    public ResponseEntity<?> getTimeline(
            @Parameter(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String userId,
            @Parameter(description = "Pagination cursor from previous response")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Number of tweets to return (max 100)")
            @RequestParam(required = false) Integer limit) {

        // Validate that path userId matches authenticated user (you can only view your own timeline)
        UserId authUserId = RequestContext.getUserId();
        if (!userId.equals(authUserId.toString())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "You can only view your own timeline", RequestContext.getRequestId()));
        }

        var userIdResult = UserId.parse(userId);
        if (userIdResult.isFailure()) {
            return toValidationErrorResponse(userIdResult.errorOrNull());
        }

        int effectiveLimit = limit != null
            ? Math.min(limit, appProperties.getTimeline().getMaxPageSize())
            : appProperties.getTimeline().getDefaultPageSize();

        Page<Tweet> page = getTimelineUseCase.getTimeline(userIdResult.getOrThrow(), cursor, effectiveLimit);
        return ResponseEntity.ok(PageResponse.from(page, TweetController.TweetResponse::from));
    }

    private ResponseEntity<ErrorResponse> toValidationErrorResponse(ValidationError error) {
        String requestId = RequestContext.getRequestId();
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(error.code(), error.message(), requestId));
    }

    public record ErrorResponse(String error, String message, String requestId) {}
}
