package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.dto.AuthResult;
import com.smartark.gateway.dto.LoginRequest;
import com.smartark.gateway.dto.RegisterRequest;
import com.smartark.gateway.entity.UserEntity;
import com.smartark.gateway.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户服务
 * <p>
 * 提供用户注册、登录认证等功能。
 * 负责用户密码的哈希处理、凭证校验以及 Token 的生成。
 * </p>
 */
@Service
public class UserService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final TokenService tokenService;

    /**
     * 构造函数
     *
     * @param userRepository 用户数据仓库
     * @param tokenService   Token 服务
     */
    public UserService(UserRepository userRepository, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * 用户注册
     * <p>
     * 检查邮箱是否已存在，若不存在则创建新用户并生成 Token。
     * </p>
     *
     * @param request 注册请求参数，包含邮箱、密码、昵称
     * @return 认证结果，包含用户基本信息和 Token
     * @throws BusinessException 如果邮箱已被注册
     */
    public AuthResult register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCodes.USER_EXISTS, "用户已存在");
        }
        Instant now = Instant.now();
        UserEntity entity = new UserEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setEmail(request.email());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setNickname(request.nickname());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        UserEntity saved = userRepository.save(entity);
        String token = tokenService.createToken(saved.getId());
        return new AuthResult(saved.getId(), saved.getEmail(), saved.getNickname(), token);
    }

    /**
     * 用户登录
     * <p>
     * 验证邮箱和密码，若通过则生成并返回新的 Token。
     * </p>
     *
     * @param request 登录请求参数，包含邮箱、密码
     * @return 认证结果，包含用户基本信息和 Token
     * @throws BusinessException 如果用户不存在或密码错误
     */
    public AuthResult login(LoginRequest request) {
        UserEntity account = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.INVALID_PASSWORD, "密码错误");
        }
        String token = tokenService.createToken(account.getId());
        return new AuthResult(account.getId(), account.getEmail(), account.getNickname(), token);
    }
}
