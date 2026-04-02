package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.UserEntity;
import com.smartark.gateway.db.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {
    @Mock
    private TokenService tokenService;
    @Mock
    private UserRepository userRepository;

    @Test
    void preHandle_shouldAllowAdminForAdminModelsPath() {
        AuthInterceptor interceptor = new AuthInterceptor(tokenService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/models");
        request.addHeader("Authorization", "Bearer token-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setRole("admin");
        when(tokenService.parseUserId("token-1")).thenReturn(Optional.of("1"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }

    @Test
    void preHandle_shouldRejectNonAdminForAdminModelsPath() {
        AuthInterceptor interceptor = new AuthInterceptor(tokenService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/models/qwen-plus/test");
        request.addHeader("Authorization", "Bearer token-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserEntity user = new UserEntity();
        user.setId(2L);
        user.setRole("user");
        when(tokenService.parseUserId("token-2")).thenReturn(Optional.of("2"));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class, () -> interceptor.preHandle(request, response, new Object()));

        assertEquals(ErrorCodes.FORBIDDEN, ex.getCode());
    }

    @Test
    void preHandle_shouldAllowInternalCallbackWithoutBearer() {
        AuthInterceptor interceptor = new AuthInterceptor(tokenService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/task/t1/step-update");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        verifyNoInteractions(tokenService, userRepository);
    }
}
