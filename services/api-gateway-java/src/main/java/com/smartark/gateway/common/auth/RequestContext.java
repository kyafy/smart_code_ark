package com.smartark.gateway.common.auth;

public final class RequestContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
