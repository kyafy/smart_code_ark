package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.BillingRecordEntity;
import com.smartark.gateway.db.entity.UserEntity;
import com.smartark.gateway.db.repo.BillingRecordRepository;
import com.smartark.gateway.db.repo.UserRepository;
import com.smartark.gateway.dto.BalanceResult;
import com.smartark.gateway.dto.BillingRecordResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BillingService {
    private final UserRepository userRepository;
    private final BillingRecordRepository billingRecordRepository;

    public BillingService(UserRepository userRepository, BillingRecordRepository billingRecordRepository) {
        this.userRepository = userRepository;
        this.billingRecordRepository = billingRecordRepository;
    }

    private Long requireUserId() {
        String userIdStr = RequestContext.getUserId();
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未授权访问");
        }
        return Long.parseLong(userIdStr);
    }

    public BalanceResult getBalance() {
        Long userId = requireUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHORIZED, "用户不存在"));
        return new BalanceResult(user.getBalance(), user.getQuota());
    }

    public List<BillingRecordResult> getRecords() {
        Long userId = requireUserId();
        return billingRecordRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> new BillingRecordResult(
                        r.getId(),
                        r.getProjectId(),
                        r.getTaskId(),
                        r.getChangeAmount(),
                        r.getCurrency(),
                        r.getReason(),
                        r.getBalanceAfter(),
                        r.getCreatedAt()
                )).toList();
    }

    @Transactional
    public void deductQuota(String projectId, String taskId, int cost, String reason) {
        Long userId = requireUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHORIZED, "用户不存在"));
        
        if (user.getQuota() == null || user.getQuota() < cost) {
            throw new BusinessException(2001, "配额不足");
        }

        user.setQuota(user.getQuota() - cost);
        userRepository.save(user);

        BillingRecordEntity record = new BillingRecordEntity();
        record.setUserId(userId);
        record.setProjectId(projectId);
        record.setTaskId(taskId);
        record.setChangeAmount(BigDecimal.valueOf(-cost));
        record.setCurrency("QUOTA");
        record.setReason(reason);
        record.setBalanceAfter(BigDecimal.valueOf(user.getQuota()));
        record.setCreatedAt(LocalDateTime.now());
        billingRecordRepository.save(record);
    }
}
