package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class LoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        String uri = request.getRequestURI();
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int status = response.getStatus();
            if (uri.startsWith("/api/")) {
                log.info("API Request: method={} uri={} status={} duration={}ms platform={} appVersion={} deviceId={} traceId={}",
                        request.getMethod(),
                        uri,
                        status,
                        duration,
                        RequestContext.getClientPlatform(),
                        RequestContext.getAppVersion(),
                        RequestContext.getDeviceId(),
                        RequestContext.getTraceId());
            }
        }
    }
}
