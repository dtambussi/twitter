package com.twitter.infrastructure.context;

import com.twitter.domain.model.UserId;
import org.slf4j.MDC;

public final class RequestContext {

    private static final String USER_ID_KEY = "userId";
    private static final String REQUEST_ID_KEY = "requestId";

    private static final ThreadLocal<UserId> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(UserId userId, String requestId) {
        currentUserId.set(userId);
        currentRequestId.set(requestId);
        MDC.put(USER_ID_KEY, userId.toString());
        MDC.put(REQUEST_ID_KEY, requestId);
    }

    public static UserId getUserId() {
        return currentUserId.get();
    }

    public static String getRequestId() {
        return currentRequestId.get();
    }

    public static void clear() {
        currentUserId.remove();
        currentRequestId.remove();
        MDC.remove(USER_ID_KEY);
        MDC.remove(REQUEST_ID_KEY);
    }
}
