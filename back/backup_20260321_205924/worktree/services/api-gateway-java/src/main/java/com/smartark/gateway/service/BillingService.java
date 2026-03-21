package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.BillingRecordEntity;
import com.smartark.gateway.db.entity.RechargeOrderEntity;
import com.smartark.gateway.db.entity.UserEntity;
import com.smartark.gateway.db.repo.BillingRecordRepository;
import com.smartark.gateway.db.repo.RechargeOrderRepository;
import com.smartark.gateway.db.repo.UserRepository;
import com.smartark.gateway.dto.BalanceResult;
import com.smartark.gateway.dto.BillingRecordResult;
import com.smartark.gateway.dto.RechargeCallbackRequest;
import com.smartark.gateway.dto.RechargeOrderCreateRequest;
import com.smartark.gateway.dto.RechargeOrderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BillingService {
    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final UserRepository userRepository;
    private final BillingRecordRepository billingRecordRepository;
    private final RechargeOrderRepository rechargeOrderRepository;
    private final boolean callbackMockSignEnabled;
    private final String callbackMockSignSecret;

    public BillingService(
            UserRepository userRepository,
            BillingRecordRepository billingRecordRepository,
            RechargeOrderRepository rechargeOrderRepository,
            @Value("${smartark.billing.recharge.callback-mock-sign-enabled:true}") boolean callbackMockSignEnabled,
            @Value("${smartark.billing.recharge.callback-mock-sign-secret:smartark-recharge-secret}") String callbackMockSignSecret
    ) {
        this.userRepository = userRepository;
        this.billingRecordRepository = billingRecordRepository;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.callbackMockSignEnabled = callbackMockSignEnabled;
        this.callbackMockSignSecret = callbackMockSignSecret;
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
    public RechargeOrderResult createRechargeOrder(RechargeOrderCreateRequest request) {
        Long userId = requireUserId();
        userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.UNAUTHORIZED, "用户不存在"));
        LocalDateTime now = LocalDateTime.now();
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderId(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setAmount(request.amount());
        order.setQuota(request.quota());
        order.setStatus("pending");
        order.setPayChannel(request.payChannel());
        order.setPaymentNo(null);
        order.setPaidAt(null);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        rechargeOrderRepository.save(order);
        return toRechargeOrderResult(order);
    }

    public RechargeOrderResult getRechargeOrder(String orderId) {
        Long userId = requireUserId();
        RechargeOrderEntity order = rechargeOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "充值订单不存在"));
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该充值订单");
        }
        return toRechargeOrderResult(order);
    }

    @Transactional
    public RechargeOrderResult handleRechargeCallback(RechargeCallbackRequest request) {
        logger.info("Recharge callback received, orderId={}, paymentNo={}", request.orderId(), request.paymentNo());
        if (!verifyCallbackSignature(request)) {
            logger.warn("Recharge callback signature invalid, orderId={}, paymentNo={}", request.orderId(), request.paymentNo());
            throw new BusinessException(ErrorCodes.FORBIDDEN, "验签失败");
        }
        logger.info("Recharge callback signature verified, orderId={}", request.orderId());

        RechargeOrderEntity order = rechargeOrderRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "充值订单不存在"));

        if ("paid".equalsIgnoreCase(order.getStatus())) {
            logger.info("Recharge callback idempotent hit by orderId, orderId={}", request.orderId());
            return toRechargeOrderResult(order);
        }

        RechargeOrderEntity paymentHandledOrder = rechargeOrderRepository
                .findByPaymentNoAndStatus(request.paymentNo(), "paid")
                .orElse(null);
        if (paymentHandledOrder != null) {
            logger.warn("Recharge callback idempotent hit by paymentNo, paymentNo={}, existingOrderId={}, callbackOrderId={}",
                    request.paymentNo(), paymentHandledOrder.getOrderId(), request.orderId());
            if (!paymentHandledOrder.getOrderId().equals(request.orderId())) {
                throw new BusinessException(ErrorCodes.FORBIDDEN, "支付单号已处理");
            }
            return toRechargeOrderResult(paymentHandledOrder);
        }

        UserEntity user = userRepository.findById(order.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
        int beforeQuota = user.getQuota() == null ? 0 : user.getQuota();
        int increaseQuota = order.getQuota() == null ? 0 : order.getQuota();
        int afterQuota = beforeQuota + increaseQuota;
        user.setQuota(afterQuota);
        userRepository.save(user);

        LocalDateTime now = LocalDateTime.now();
        order.setStatus("paid");
        order.setPaymentNo(request.paymentNo());
        order.setPaidAt(now);
        if (request.payChannel() != null && !request.payChannel().isBlank()) {
            order.setPayChannel(request.payChannel());
        }
        order.setUpdatedAt(now);
        rechargeOrderRepository.save(order);

        BillingRecordEntity record = new BillingRecordEntity();
        record.setUserId(order.getUserId());
        record.setProjectId(null);
        record.setTaskId(null);
        record.setChangeAmount(BigDecimal.valueOf(increaseQuota));
        record.setCurrency("QUOTA");
        record.setReason("recharge");
        record.setBalanceAfter(BigDecimal.valueOf(afterQuota));
        record.setCreatedAt(now);
        billingRecordRepository.save(record);

        logger.info("Recharge callback credited, orderId={}, paymentNo={}, quotaBefore={}, quotaIncrease={}, quotaAfter={}",
                order.getOrderId(), request.paymentNo(), beforeQuota, increaseQuota, afterQuota);
        return toRechargeOrderResult(order);
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

    private RechargeOrderResult toRechargeOrderResult(RechargeOrderEntity order) {
        return new RechargeOrderResult(
                order.getOrderId(),
                order.getStatus(),
                order.getAmount(),
                order.getQuota(),
                order.getPayChannel(),
                order.getPaymentNo(),
                order.getPaidAt(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private boolean verifyCallbackSignature(RechargeCallbackRequest request) {
        if (!callbackMockSignEnabled) {
            return true;
        }
        String source = request.orderId() + "|" + request.paymentNo() + "|" + callbackMockSignSecret;
        return source.equals(request.signature());
    }
}
