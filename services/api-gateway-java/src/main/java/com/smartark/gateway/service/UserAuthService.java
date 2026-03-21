package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.UserEntity;
import com.smartark.gateway.db.repo.UserRepository;
import com.smartark.gateway.dto.AuthResult;
import com.smartark.gateway.dto.LoginRequest;
import com.smartark.gateway.dto.RegisterRequest;
import com.smartark.gateway.dto.RegisterResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserAuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public UserAuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public RegisterResult register(RegisterRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new BusinessException(ErrorCodes.CONFLICT, "用户已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("user");
        user.setBalance(BigDecimal.ZERO);
        user.setQuota(0);
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        UserEntity saved = userRepository.save(user);
        return new RegisterResult(saved.getId());
    }

    public AuthResult login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = tokenService.issueToken(String.valueOf(user.getId()));
        return new AuthResult(token, user.getId());
    }

    @Transactional
    public AuthResult loginBySms(String phone, String captcha) {
        Optional<UserEntity> existing = userRepository.findByUsername(phone);
        UserEntity user = existing.orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setUsername(phone);
            u.setPasswordHash(passwordEncoder.encode("sms:" + phone));
            u.setRole("user");
            u.setBalance(BigDecimal.ZERO);
            u.setQuota(0);
            LocalDateTime now = LocalDateTime.now();
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            return userRepository.save(u);
        });
        String token = tokenService.issueToken(String.valueOf(user.getId()));
        return new AuthResult(token, user.getId());
    }
}
