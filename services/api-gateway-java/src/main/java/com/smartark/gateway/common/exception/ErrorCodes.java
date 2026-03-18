package com.smartark.gateway.common.exception;

public final class ErrorCodes {
    public static final int OK = 0;

    public static final int VALIDATION_FAILED = 1001;
    public static final int UNAUTHORIZED = 1002;
    public static final int FORBIDDEN = 1003;
    public static final int NOT_FOUND = 1004;
    public static final int CONFLICT = 1005;
    public static final int TOO_MANY_REQUESTS = 1006;

    public static final int QUOTA_INSUFFICIENT = 2001;

    public static final int MODEL_SERVICE_ERROR = 3001;
    public static final int TASK_FAILED = 3002;
    public static final int TASK_TIMEOUT = 3003;

    public static final int INTERNAL_ERROR = 9000;

    private ErrorCodes() {
    }
}
