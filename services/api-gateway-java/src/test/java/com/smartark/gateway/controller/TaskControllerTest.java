package com.smartark.gateway.controller;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.exception.GlobalExceptionHandler;
import com.smartark.gateway.dto.ContractReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskService taskService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TaskController controller = new TaskController(taskService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getContractReport_success() throws Exception {
        ContractReportResult result = new ContractReportResult(
                true,
                List.of(),
                List.of("generated_scripts_start_sh"),
                "2026-03-24T12:00:00"
        );
        when(taskService.getContractReport("task-1")).thenReturn(result);

        mvc.perform(get("/api/task/task-1/contract-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.fixedActions[0]").value("generated_scripts_start_sh"));
    }

    @Test
    void getContractReport_notFound() throws Exception {
        when(taskService.getContractReport("missing"))
                .thenThrow(new BusinessException(ErrorCodes.DELIVERY_REPORT_NOT_FOUND, "contract_report.json not found"));

        mvc.perform(get("/api/task/missing/contract-report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.DELIVERY_REPORT_NOT_FOUND))
                .andExpect(jsonPath("$.message").value("contract_report.json not found"));
    }

    @Test
    void getDeliveryReport_success() throws Exception {
        DeliveryReportResult result = new DeliveryReportResult(
                "task-5",
                "deliverable",
                "deliverable",
                "passed",
                true,
                List.of(),
                List.of(),
                new DeliveryReportResult.ReportRefs("contract_report.json", "build_verify_report.json", "runtime_smoke_test_report.json"),
                "2026-03-24T12:03:00"
        );
        when(taskService.getDeliveryReport("task-5")).thenReturn(result);

        mvc.perform(get("/api/task/task-5/delivery-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deliveryLevelRequested").value("deliverable"))
                .andExpect(jsonPath("$.data.deliveryLevelActual").value("deliverable"))
                .andExpect(jsonPath("$.data.status").value("passed"));
    }

    @Test
    void getDeliveryReport_notFound() throws Exception {
        when(taskService.getDeliveryReport("missing"))
                .thenThrow(new BusinessException(ErrorCodes.DELIVERY_REPORT_NOT_FOUND, "delivery_report.json not found"));

        mvc.perform(get("/api/task/missing/delivery-report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.DELIVERY_REPORT_NOT_FOUND))
                .andExpect(jsonPath("$.message").value("delivery_report.json not found"));
    }

    @Test
    void validateDelivery_autoFixFalse_success() throws Exception {
        ContractReportResult result = new ContractReportResult(
                false,
                List.of("invalid_start_script"),
                List.of(),
                "2026-03-24T12:01:00"
        );
        when(taskService.validateDelivery("task-2", false)).thenReturn(result);

        mvc.perform(post("/api/task/task-2/delivery/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFix": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failedRules[0]").value("invalid_start_script"));
    }

    @Test
    void validateDelivery_autoFixTrue_success() throws Exception {
        ContractReportResult result = new ContractReportResult(
                true,
                List.of(),
                List.of("repaired_compose_context:frontend"),
                "2026-03-24T12:02:00"
        );
        when(taskService.validateDelivery("task-3", true)).thenReturn(result);

        mvc.perform(post("/api/task/task-3/delivery/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFix": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.fixedActions[0]").value("repaired_compose_context:frontend"));
    }

    @Test
    void validateDelivery_forbidden() throws Exception {
        when(taskService.validateDelivery("task-4", true))
                .thenThrow(new BusinessException(ErrorCodes.FORBIDDEN, "forbidden"));

        mvc.perform(post("/api/task/task-4/delivery/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFix": true
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("forbidden"));
    }
}
