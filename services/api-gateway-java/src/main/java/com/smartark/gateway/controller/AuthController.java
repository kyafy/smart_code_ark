package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.AuthResult;
import com.smartark.gateway.dto.LoginRequest;
import com.smartark.gateway.dto.RegisterRequest;
import com.smartark.gateway.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证控制器
 * <p>
 * 处理用户注册和登录请求。
 * 对应 /api/v1/auth 路径。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService userService;

    /**
     * 构造函数
     *
     * @param userService 用户服务
     */
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册
     *
     * @param request 注册请求体
     * @return 注册结果，包含 Token
     */
    @PostMapping("/register")
    public ApiResponse<AuthResult> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(userService.register(request));
    }

    /**
     * 用户登录
     *
     * @param request 登录请求体
     * @return 登录结果，包含 Token
     */
    @PostMapping("/login")
    public ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(userService.login(request));
    }
}
