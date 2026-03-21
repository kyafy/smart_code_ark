package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.UserProfileResult;
import com.smartark.gateway.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public ApiResponse<UserProfileResult> getProfile() {
        return ApiResponse.success(userService.getProfile());
    }
}
