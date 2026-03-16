package com.smartark.gateway.common.exception;

public final class ErrorCodes {
    public static final int UNAUTHORIZED = 40100;
    public static final int USER_EXISTS = 40010;
    public static final int USER_NOT_FOUND = 40011;
    public static final int INVALID_PASSWORD = 40012;
    public static final int PROJECT_NOT_FOUND = 40020;
    public static final int TASK_NOT_FOUND = 40030;
    public static final int TASK_STATUS_INVALID = 40031;
    public static final int DOWNSTREAM_FORMAT = 50001;
    public static final int DOWNSTREAM_FAILED = 50002;
    public static final int DOWNSTREAM_TIMEOUT = 50401;

    private ErrorCodes() {
    }
}
