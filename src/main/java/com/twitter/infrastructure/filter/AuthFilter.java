package com.twitter.infrastructure.filter;

import com.twitter.application.port.out.UserRepository;
import com.twitter.domain.model.User;
import com.twitter.domain.model.UserId;
import com.twitter.infrastructure.context.RequestContext;
import com.twitter.infrastructure.sharding.ShardContext;
import com.twitter.infrastructure.sharding.ShardRouter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/actuator",
        "/api-docs",
        "/v3/api-docs",
        "/api/v1/demo",
        "/docs.html"
    );

    private final UserRepository userRepository;
    private final ShardRouter shardRouter;

    public AuthFilter(UserRepository userRepository, ShardRouter shardRouter) {
        this.userRepository = userRepository;
        this.shardRouter = shardRouter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String requestId = getOrGenerateRequestId(request);

        // Allow public paths without auth
        if (isPublicPath(path)) {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            try {
                filterChain.doFilter(request, response);
            } finally {
                RequestContext.clear();
                ShardContext.clear();
            }
            return;
        }

        String userIdHeader = request.getHeader(USER_ID_HEADER);

        if (userIdHeader == null || userIdHeader.isBlank()) {
            log.warn("Missing {} header for path: {}", USER_ID_HEADER, path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":\"UNAUTHORIZED\",\"message\":\"Missing " + USER_ID_HEADER + " header\",\"requestId\":\"" + requestId + "\"}"
            );
            return;
        }

        var userIdResult = UserId.parse(userIdHeader);
        if (userIdResult.isFailure()) {
            var error = userIdResult.errorOrNull();
            log.warn("Invalid {} header: {}", USER_ID_HEADER, error.message());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":\"" + error.code() + "\",\"message\":\"" + error.message() + "\",\"requestId\":\"" + requestId + "\"}"
            );
            return;
        }

        UserId userId = userIdResult.getOrThrow();

        // Set shard context BEFORE any database operations
        int shardId = shardRouter.getShardForUser(userId);
        ShardContext.set(shardId);

        // Upsert user (ensure exists) - now routed to correct shard
        userRepository.upsert(User.create(userId));

        RequestContext.set(userId, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        log.debug("Request authenticated: userId={}, requestId={}, shard={}, path={}", userId, requestId, shardId, path);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            ShardContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private String getOrGenerateRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }
}
