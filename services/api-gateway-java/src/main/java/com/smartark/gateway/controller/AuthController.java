package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.AuthResult;
import com.smartark.gateway.dto.LoginRequest;
import com.smartark.gateway.dto.RegisterRequest;
import com.smartark.gateway.dto.RegisterResult;
import com.smartark.gateway.dto.SmsLoginRequest;
import com.smartark.gateway.dto.SmsSendRequest;
import com.smartark.gateway.dto.SmsSendResult;
import com.smartark.gateway.service.SmsCodeService;
import com.smartark.gateway.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {
    private final UserAuthService userAuthService;
    private final SmsCodeService smsCodeService;

    public AuthController(UserAuthService userAuthService, SmsCodeService smsCodeService) {
        this.userAuthService = userAuthService;
        this.smsCodeService = smsCodeService;
    }

    @PostMapping("/register")
    public ApiResponse<RegisterResult> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(userAuthService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(userAuthService.login(request));
    }

    @PostMapping("/sms/send")
    public ApiResponse<SmsSendResult> sendSms(@Valid @RequestBody SmsSendRequest request, HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        smsCodeService.sendCode(request.phone(), request.scene(), ip);
        String requestId = "sms_req_" + System.currentTimeMillis();
        return ApiResponse.success(new SmsSendResult(requestId, 300));
    }

    @PostMapping("/login/sms")
    public ApiResponse<AuthResult> loginSms(@Valid @RequestBody SmsLoginRequest request) {
        smsCodeService.verifyCode(request.phone(), "login", request.captcha());
        return ApiResponse.success(userAuthService.loginBySms(request.phone(), request.captcha()));
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
