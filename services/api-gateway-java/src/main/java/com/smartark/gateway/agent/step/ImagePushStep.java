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
public class ImagePushStep implements AgentStep {
    private final ReleaseDeployService releaseDeployService;
    private final TaskRepository taskRepository;

    public ImagePushStep(ReleaseDeployService releaseDeployService, TaskRepository taskRepository) {
        this.releaseDeployService = releaseDeployService;
        this.taskRepository = taskRepository;
    }

    @Override
    public String getStepCode() {
        return "image_push";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        TaskEntity task = context.getTask();
        ReleaseDeployService.ReleaseReport report = releaseDeployService.pushImages(task, context.getWorkspaceDir());
        task.setReleaseStatus(report.skipped() ? task.getReleaseStatus() : (report.passed() ? "image_pushed" : "image_push_failed"));
        taskRepository.save(task);

        context.logInfo("Image push result: passed=" + report.passed()
                + ", skipped=" + report.skipped()
                + ", issues=" + report.issues().size()
                + ", warnings=" + report.warnings().size());

        if (!report.skipped() && !report.passed() && releaseDeployService.isStrict(task)) {
            throw new BusinessException(ErrorCodes.RELEASE_IMAGE_PUSH_FAILED, "image push failed in strict mode");
        }
    }
}
