package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.BalanceResult;
import com.smartark.gateway.dto.BillingRecordResult;
import com.smartark.gateway.dto.RechargeCallbackRequest;
import com.smartark.gateway.dto.RechargeOrderCreateRequest;
import com.smartark.gateway.dto.RechargeOrderResult;
import com.smartark.gateway.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Billing", description = "Balance, billing records, recharge order and callback APIs")
public class BillingController {
    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/balance")
    @Operation(summary = "Get current balance")
    public ApiResponse<BalanceResult> getBalance() {
        return ApiResponse.success(billingService.getBalance());
    }

    @GetMapping("/records")
    @Operation(summary = "List billing records")
    public ApiResponse<List<BillingRecordResult>> getRecords() {
        return ApiResponse.success(billingService.getRecords());
    }

    @PostMapping("/recharge/orders")
    @Operation(summary = "Create recharge order")
    public ApiResponse<RechargeOrderResult> createRechargeOrder(@Valid @RequestBody RechargeOrderCreateRequest request) {
        return ApiResponse.success(billingService.createRechargeOrder(request));
    }

    @GetMapping("/recharge/orders/{orderId}")
    @Operation(summary = "Get recharge order")
    public ApiResponse<RechargeOrderResult> getRechargeOrder(
            @Parameter(description = "Recharge order ID", required = true) @PathVariable String orderId) {
        return ApiResponse.success(billingService.getRechargeOrder(orderId));
    }

    @PostMapping("/recharge/callback")
    @Operation(summary = "Recharge callback", description = "Callback endpoint invoked by payment provider.")
    public ApiResponse<RechargeOrderResult> rechargeCallback(@Valid @RequestBody RechargeCallbackRequest request) {
        return ApiResponse.success(billingService.handleRechargeCallback(request));
    }
}
