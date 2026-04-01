package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.service.ReleaseDeployService;
import org.springframework.stereotype.Component;

@Component
public class DeployRollbackStep implements AgentStep {
    private final ReleaseDeployService releaseDeployService;
    private final TaskRepository taskRepository;

    public DeployRollbackStep(ReleaseDeployService releaseDeployService, TaskRepository taskRepository) {
        this.releaseDeployService = releaseDeployService;
        this.taskRepository = taskRepository;
    }

    @Override
    public String getStepCode() {
        return "deploy_rollback";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        TaskEntity task = context.getTask();
        boolean rollbackRequired = releaseDeployService.needsRollback(context.getWorkspaceDir());
        ReleaseDeployService.ReleaseReport report = releaseDeployService.rollbackIfNeeded(task, context.getWorkspaceDir());

        if (!report.skipped() && report.passed()) {
            task.setReleaseStatus("rolled_back");
        } else if (!report.skipped()) {
            task.setReleaseStatus("rollback_failed");
        }
        taskRepository.save(task);

        context.logInfo("Deploy rollback result: passed=" + report.passed()
                + ", skipped=" + report.skipped()
                + ", issues=" + report.issues().size()
                + ", warnings=" + report.warnings().size());

        if (!report.skipped() && !report.passed()) {
            throw new BusinessException(ErrorCodes.RELEASE_ROLLBACK_FAILED, "deploy rollback failed");
        }
        if (rollbackRequired && releaseDeployService.isStrict(task)) {
            throw new BusinessException(ErrorCodes.RELEASE_VERIFY_FAILED, "deploy verify failed and rollback executed in strict mode");
        }
        if (rollbackRequired && !releaseDeployService.isStrict(task)) {
            context.logWarn("deploy verify failed but strict mode is disabled; rollback executed");
        }
    }
}
