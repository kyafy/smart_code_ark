package com.smartark.gateway.common.auth;

public final class RequestContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
<<<<<<< HEAD
=======
    private static final ThreadLocal<String> CLIENT_PLATFORM = new ThreadLocal<>();
    private static final ThreadLocal<String> APP_VERSION = new ThreadLocal<>();
    private static final ThreadLocal<String> DEVICE_ID = new ThreadLocal<>();
>>>>>>> origin/master

    private RequestContext() {
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

<<<<<<< HEAD
    public static void clear() {
        USER_ID.remove();
        TRACE_ID.remove();
=======
    public static void setClientPlatform(String clientPlatform) {
        CLIENT_PLATFORM.set(clientPlatform);
    }

    public static String getClientPlatform() {
        return CLIENT_PLATFORM.get();
    }

    public static void setAppVersion(String appVersion) {
        APP_VERSION.set(appVersion);
    }

    public static String getAppVersion() {
        return APP_VERSION.get();
    }

    public static void setDeviceId(String deviceId) {
        DEVICE_ID.set(deviceId);
    }

    public static String getDeviceId() {
        return DEVICE_ID.get();
    }

    public static ClientContext getClientContext() {
        return new ClientContext(getClientPlatform(), getAppVersion(), getDeviceId());
    }

    public static void clear() {
        USER_ID.remove();
        TRACE_ID.remove();
        CLIENT_PLATFORM.remove();
        APP_VERSION.remove();
        DEVICE_ID.remove();
>>>>>>> origin/master
    }
}
