package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.GenerateOptions;
import com.smartark.gateway.service.RuntimeSmokeTestService;
import org.springframework.stereotype.Component;

@Component
public class RuntimeSmokeTestStep implements AgentStep {
    private final RuntimeSmokeTestService runtimeSmokeTestService;
    private final TaskRepository taskRepository;

    public RuntimeSmokeTestStep(RuntimeSmokeTestService runtimeSmokeTestService, TaskRepository taskRepository) {
        this.runtimeSmokeTestService = runtimeSmokeTestService;
        this.taskRepository = taskRepository;
    }

    @Override
    public String getStepCode() {
        return "runtime_smoke_test";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        TaskEntity task = context.getTask();
        RuntimeSmokeTestService.RuntimeSmokeTestBundle bundle = runtimeSmokeTestService.verify(task, context.getWorkspaceDir());
        task.setDeliveryLevelActual(bundle.deliveryReport().deliveryLevelActual());
        task.setDeliveryStatus(bundle.deliveryReport().status());
        taskRepository.save(task);

        context.logInfo("Runtime smoke result: requested=" + bundle.deliveryReport().deliveryLevelRequested()
                + ", actual=" + bundle.deliveryReport().deliveryLevelActual()
                + ", status=" + bundle.deliveryReport().status()
                + ", passed=" + bundle.deliveryReport().passed());

        String requested = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());
        if ("deliverable".equals(requested) && !bundle.deliveryReport().passed()) {
            throw new BusinessException(
                    ErrorCodes.RUNTIME_SMOKE_TEST_FAILED,
                    "runtime smoke test failed: requested=" + requested
                            + ", actual=" + bundle.deliveryReport().deliveryLevelActual()
                            + ", status=" + bundle.deliveryReport().status()
            );
        }
    }
}
