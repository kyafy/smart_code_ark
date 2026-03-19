package com.smartark.gateway.agent;

import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectSpecRepository projectSpecRepository;
    private final Map<String, AgentStep> stepMap;

    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;

    public AgentOrchestrator(TaskRepository taskRepository,
                             TaskStepRepository taskStepRepository,
                             TaskLogRepository taskLogRepository,
                             ProjectSpecRepository projectSpecRepository,
                             List<AgentStep> agentSteps) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.stepMap = agentSteps.stream().collect(Collectors.toMap(AgentStep::getStepCode, step -> step));
    }

    public void run(String taskId) {
        try {
            TaskEntity task = taskRepository.findById(taskId).orElseThrow();
            task.setStatus("running");
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            log(taskId, "info", "Task started: " + taskId);

            ProjectSpecEntity spec = projectSpecRepository.findTopByProjectIdOrderByVersionDesc(task.getProjectId())
                    .orElseThrow(() -> new RuntimeException("Project spec not found"));

            AgentExecutionContext context = new AgentExecutionContext();
            context.setTask(task);
            context.setSpec(spec);
            context.setInstructions(task.getInstructions());
            Path workspaceDir = Paths.get(workspaceRoot, taskId);
            context.setWorkspaceDir(workspaceDir);

            List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);

            for (int i = 0; i < steps.size(); i++) {
                TaskStepEntity stepEntity = steps.get(i);
                
                stepEntity.setStatus("running");
                stepEntity.setStartedAt(LocalDateTime.now());
                stepEntity.setUpdatedAt(LocalDateTime.now());
                taskStepRepository.save(stepEntity);

                task.setCurrentStep(stepEntity.getStepCode());
                task.setProgress((i * 100) / steps.size());
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);

                log(taskId, "info", "Executing step: " + stepEntity.getStepName());

                try {
                    AgentStep agentStep = stepMap.get(stepEntity.getStepCode());
                    if (agentStep == null) {
                        throw new RuntimeException("Unsupported step code: " + stepEntity.getStepCode());
                    }
                    agentStep.execute(context);
                } catch (Exception e) {
                    log(taskId, "error", "Step failed: " + e.getMessage());
                    throw e;
                }

                stepEntity.setStatus("finished");
                stepEntity.setProgress(100);
                stepEntity.setFinishedAt(LocalDateTime.now());
                stepEntity.setUpdatedAt(LocalDateTime.now());
                taskStepRepository.save(stepEntity);
            }

            task.setStatus("finished");
            task.setProgress(100);
            task.setCurrentStep("package");
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            log(taskId, "info", "Task finished successfully");

        } catch (Exception e) {
            logger.error("Task execution failed", e);
            log(taskId, "error", "Task failed: " + e.getMessage());
            taskRepository.findById(taskId).ifPresent(t -> {
                t.setStatus("failed");
                t.setErrorMessage(e.getMessage());
                t.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(t);
            });
        }
    }

    private void log(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }
}
