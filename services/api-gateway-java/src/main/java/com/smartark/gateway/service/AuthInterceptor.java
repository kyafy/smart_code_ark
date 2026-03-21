package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final String HEADER_CLIENT_PLATFORM = "X-Client-Platform";
    private static final String HEADER_APP_VERSION = "X-App-Version";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";
    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        bindClientMetadata(request);
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/") || path.startsWith("/api/v1/auth/")
                || path.equals("/api/health") || path.equals("/api/v1/health")
                || path.equals("/api/billing/recharge/callback")
                || path.equals("/error")) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未授权访问");
        }
        String token = header.substring("Bearer ".length()).trim();
        String userId = tokenService.parseUserId(token)
                .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHORIZED, "无效令牌"));
        RequestContext.setUserId(userId);
        return true;
    }

    private void bindClientMetadata(HttpServletRequest request) {
        String platform = request.getHeader(HEADER_CLIENT_PLATFORM);
        String appVersion = request.getHeader(HEADER_APP_VERSION);
        String deviceId = request.getHeader(HEADER_DEVICE_ID);
        RequestContext.setClientPlatform(platform == null || platform.isBlank() ? "web" : platform.trim());
        RequestContext.setAppVersion(appVersion == null ? "" : appVersion.trim());
        RequestContext.setDeviceId(deviceId == null ? "" : deviceId.trim());
    }
}
