package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.UserEntity;
import com.smartark.gateway.db.repo.UserRepository;
import com.smartark.gateway.dto.UserProfileResult;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserProfileResult getProfile() {
        Long userId = requireUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
        return new UserProfileResult(
                user.getId(),
                user.getUsername(),
                user.getBalance(),
                user.getQuota(),
                user.getCreatedAt()
        );
    }

    private Long requireUserId() {
        String userIdStr = RequestContext.getUserId();
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未授权访问");
        }
        try {
            return Long.parseLong(userIdStr);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未授权访问");
        }
    }
}
