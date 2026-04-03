package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.DeepAgentCodegenRunRequest;
import com.smartark.gateway.dto.DeepAgentCodegenRunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dispatches generate/modify tasks to the DeepAgent runtime.
 */
@Service
public class DeepAgentExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(DeepAgentExecutorService.class);

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSpecRepository projectSpecRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${smartark.langchain.runtime.base-url:http://localhost:18080}")
    private String runtimeBaseUrl;
    @Value("${smartark.agent.deepagent.codegen-path:/v1/agent/codegen/run}")
    private String codegenPath;
    @Value("${smartark.agent.deepagent.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;
    @Value("${smartark.agent.deepagent.callback-token:smartark-internal}")
    private String callbackToken;
    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;

    public DeepAgentExecutorService(TaskRepository taskRepository,
                                    TaskLogRepository taskLogRepository,
                                    ProjectRepository projectRepository,
                                    ProjectSpecRepository projectSpecRepository,
                                    ObjectMapper objectMapper,
                                    @Value("${smartark.langchain.runtime.timeout-ms:600000}") int timeoutMs) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectRepository = projectRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.objectMapper = objectMapper;

        int effectiveTimeout = Math.max(timeoutMs, 1000);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(effectiveTimeout);
        factory.setReadTimeout(effectiveTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public void run(String taskId, TaskExecutionModeResolver.TaskExecutionDecision decision) {
        Optional<TaskEntity> taskOptional = taskRepository.findById(taskId);
        if (taskOptional.isEmpty()) {
            logger.warn("Deepagent dispatch skipped because task not found: {}", taskId);
            return;
        }

        TaskEntity task = taskOptional.get();
        try {
            markTaskRunning(task, decision);
            DeepAgentCodegenRunRequest payload = buildCodegenRequest(task);
            DeepAgentCodegenRunResponse response = restClient.post()
                    .uri(buildUrl(codegenPath))
                    .body(payload)
                    .retrieve()
                    .body(DeepAgentCodegenRunResponse.class);

            String runId = response == null ? "" : nullable(response.runId());
            appendTaskLog(taskId, "info",
                    "deepagent dispatch accepted, runId=" + runId
                            + ", selectedMode=" + decision.selectedMode()
                            + ", reason=" + decision.reason());
        } catch (Exception e) {
            logger.error("Deepagent dispatch failed for task {}", taskId, e);
            task.setStatus("failed");
            task.setErrorCode(String.valueOf(ErrorCodes.TASK_MODEL_ERROR));
            task.setErrorMessage(truncate("deepagent dispatch failed: " + messageOf(e), 255));
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
            appendTaskLog(taskId, "error", "deepagent dispatch failed: " + messageOf(e));
        }
    }

    private void markTaskRunning(TaskEntity task, TaskExecutionModeResolver.TaskExecutionDecision decision) {
        task.setStatus("running");
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        appendTaskLog(task.getId(), "info",
                "Task dispatched to deepagent, configuredMode=" + decision.configuredMode()
                        + ", selectedMode=" + decision.selectedMode()
                        + ", reason=" + decision.reason());
    }

    private DeepAgentCodegenRunRequest buildCodegenRequest(TaskEntity task) {
        ProjectEntity project = projectRepository.findById(task.getProjectId()).orElse(null);
        ProjectSpecEntity projectSpec = projectSpecRepository.findTopByProjectIdOrderByVersionDesc(task.getProjectId()).orElse(null);
        String stackBackend = defaultIfBlank(project == null ? null : project.getStackBackend(), "springboot");
        String stackFrontend = defaultIfBlank(project == null ? null : project.getStackFrontend(), "vue3");
        String stackDb = defaultIfBlank(project == null ? null : project.getStackDb(), "mysql");
        String prd = extractPrd(projectSpec);

        DeepAgentCodegenRunRequest.StackConfig stackConfig =
                new DeepAgentCodegenRunRequest.StackConfig(stackBackend, stackFrontend, stackDb);

        // llm_config: null means DeepAgent uses its runtime defaults.
        // Extend this map when task-level model routing is needed (e.g. large-context tasks).
        Map<String, Object> llmConfig = null;

        return new DeepAgentCodegenRunRequest(
                task.getId(),
                task.getProjectId(),
                task.getUserId(),
                nullable(task.getInstructions()),
                prd,
                stackConfig,
                nullable(task.getTemplateId()),
                Paths.get(workspaceRoot, task.getId()).toAbsolutePath().toString(),
                callbackBaseUrl,
                callbackToken,
                Map.of(),
                llmConfig
        );
    }

    private String extractPrd(ProjectSpecEntity projectSpec) {
        if (projectSpec == null || projectSpec.getRequirementJson() == null || projectSpec.getRequirementJson().isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(projectSpec.getRequirementJson());
            return nullable(root.path("prd").asText(""));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Notify the Python DeepAgent runtime to gracefully cancel a running pipeline.
     * Best-effort: failure to reach the runtime does not break the cancel flow.
     */
    public void cancelRuntime(String taskId) {
        try {
            restClient.post()
                    .uri(buildUrl("/v1/agent/codegen/cancel/" + taskId))
                    .retrieve()
                    .toBodilessEntity();
            logger.info("Sent cancel to deepagent runtime for task {}", taskId);
        } catch (Exception e) {
            logger.warn("Failed to notify deepagent cancel for {}: {}", taskId, e.getMessage());
        }
    }

    private String buildUrl(String path) {
        String baseUrl = nullable(runtimeBaseUrl);
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("smartark.langchain.runtime.base-url is empty");
        }
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String normalizedPath = path == null ? "" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (normalizedBase.endsWith("/v1") && normalizedPath.startsWith("/v1/")) {
            return normalizedBase + normalizedPath.substring(3);
        }
        return normalizedBase + normalizedPath;
    }

    private void appendTaskLog(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(defaultIfBlank(level, "info").toLowerCase(Locale.ROOT));
        log.setContent(nullable(content));
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String nullable(String value) {
        return value == null ? "" : value.trim();
    }

    private String messageOf(Throwable throwable) {
        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return msg;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
