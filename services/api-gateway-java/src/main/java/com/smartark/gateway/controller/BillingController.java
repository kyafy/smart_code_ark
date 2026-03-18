package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.BalanceResult;
import com.smartark.gateway.dto.BillingRecordResult;
import com.smartark.gateway.service.BillingService;
import org.springframework.web.bind.annotation.GetMapping;
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
}
