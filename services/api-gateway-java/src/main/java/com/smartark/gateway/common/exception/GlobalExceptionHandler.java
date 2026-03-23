package com.smartark.gateway.common.exception;

import com.smartark.gateway.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case ErrorCodes.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.CONFLICT -> HttpStatus.CONFLICT;
            case ErrorCodes.TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case ErrorCodes.QUOTA_INSUFFICIENT -> HttpStatus.PAYMENT_REQUIRED;
            case ErrorCodes.MODEL_SERVICE_ERROR -> HttpStatus.BAD_GATEWAY;
            case ErrorCodes.TASK_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case ErrorCodes.PREVIEW_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case ErrorCodes.PREVIEW_REBUILD_STATE_INVALID -> HttpStatus.CONFLICT;
            case ErrorCodes.PREVIEW_CONCURRENCY_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case ErrorCodes.PREVIEW_BUILD_FAILED, ErrorCodes.PREVIEW_START_FAILED, ErrorCodes.PREVIEW_PROXY_FAILED -> HttpStatus.BAD_GATEWAY;
            case ErrorCodes.TASK_FAILED, ErrorCodes.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        if (status.is5xxServerError()) {
            log.warn("business_error code={} message={}", ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(ErrorCodes.VALIDATION_FAILED, message));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCodes.NOT_FOUND, "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCodes.CONFLICT, "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("unhandled_error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCodes.INTERNAL_ERROR, "系统内部错误"));
    }
}
