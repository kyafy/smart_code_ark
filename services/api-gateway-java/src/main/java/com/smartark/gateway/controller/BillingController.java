package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.BalanceResult;
import com.smartark.gateway.dto.BillingRecordResult;
import com.smartark.gateway.dto.RechargeCallbackRequest;
import com.smartark.gateway.dto.RechargeOrderCreateRequest;
import com.smartark.gateway.dto.RechargeOrderResult;
import com.smartark.gateway.service.BillingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/balance")
    public ApiResponse<BalanceResult> getBalance() {
        return ApiResponse.success(billingService.getBalance());
    }

    @GetMapping("/records")
    public ApiResponse<List<BillingRecordResult>> getRecords() {
        return ApiResponse.success(billingService.getRecords());
    }

    @PostMapping("/recharge/orders")
    public ApiResponse<RechargeOrderResult> createRechargeOrder(@Valid @RequestBody RechargeOrderCreateRequest request) {
        return ApiResponse.success(billingService.createRechargeOrder(request));
    }

    @GetMapping("/recharge/orders/{orderId}")
    public ApiResponse<RechargeOrderResult> getRechargeOrder(@PathVariable String orderId) {
        return ApiResponse.success(billingService.getRechargeOrder(orderId));
    }

    @PostMapping("/recharge/callback")
    public ApiResponse<RechargeOrderResult> rechargeCallback(@Valid @RequestBody RechargeCallbackRequest request) {
        return ApiResponse.success(billingService.handleRechargeCallback(request));
    }
}
