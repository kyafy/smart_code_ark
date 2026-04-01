package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.service.ReleaseDeployService;
import org.springframework.stereotype.Component;

@Component
public class DeployVerifyStep implements AgentStep {
    private final ReleaseDeployService releaseDeployService;
    private final TaskRepository taskRepository;

    public DeployVerifyStep(ReleaseDeployService releaseDeployService, TaskRepository taskRepository) {
        this.releaseDeployService = releaseDeployService;
        this.taskRepository = taskRepository;
    }

    @Override
    public String getStepCode() {
        return "deploy_verify";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        TaskEntity task = context.getTask();
        ReleaseDeployService.ReleaseReport report = releaseDeployService.verifyTarget(task, context.getWorkspaceDir());
        task.setReleaseStatus(report.skipped() ? task.getReleaseStatus() : (report.passed() ? "deploy_verified" : "verify_failed"));
        taskRepository.save(task);

        context.logInfo("Deploy verify result: passed=" + report.passed()
                + ", skipped=" + report.skipped()
                + ", issues=" + report.issues().size()
                + ", warnings=" + report.warnings().size());

        if (!report.skipped() && !report.passed()) {
            context.logWarn("Deploy verify failed, continue to deploy_rollback");
        }
    }
}
