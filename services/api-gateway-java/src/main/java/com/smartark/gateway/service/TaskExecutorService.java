package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TaskExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(TaskExecutorService.class);
    
    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ArtifactRepository artifactRepository;
    private final ProjectSpecRepository projectSpecRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public TaskExecutorService(TaskRepository taskRepository,
                               TaskStepRepository taskStepRepository,
                               TaskLogRepository taskLogRepository,
                               ArtifactRepository artifactRepository,
                               ProjectSpecRepository projectSpecRepository,
                               ModelService modelService,
                               ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.artifactRepository = artifactRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void executeTask(String taskId) {
        try {
            TaskEntity task = taskRepository.findById(taskId).orElseThrow();
            task.setStatus("running");
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            log(taskId, "info", "Task started: " + taskId);

            // 1. Prepare Data
            ProjectSpecEntity spec = projectSpecRepository.findTopByProjectIdOrderByVersionDesc(task.getProjectId())
                    .orElseThrow(() -> new RuntimeException("Project spec not found"));
            
            JsonNode reqJson = objectMapper.readTree(spec.getRequirementJson());
            String prd = reqJson.path("prd").asText("");
            String stackBackend = reqJson.at("/stack/backend").asText("springboot");
            String stackFrontend = reqJson.at("/stack/frontend").asText("vue3");
            String stackDb = reqJson.at("/stack/db").asText("mysql");
            String fullStack = "Backend: " + stackBackend + ", Frontend: " + stackFrontend + ", Database: " + stackDb;

            List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);
            List<String> fileList = new ArrayList<>();
            
            for (int i = 0; i < steps.size(); i++) {
                TaskStepEntity step = steps.get(i);
                
                // Update step status
                step.setStatus("running");
                step.setStartedAt(LocalDateTime.now());
                step.setUpdatedAt(LocalDateTime.now());
                taskStepRepository.save(step);

                task.setCurrentStep(step.getStepCode());
                task.setProgress((i * 100) / steps.size());
                task.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);

                log(taskId, "info", "Executing step: " + step.getStepName());

                try {
                    switch (step.getStepCode()) {
                        case "requirement_analyze":
                            log(taskId, "info", "Analyzing requirements and planning project structure...");
                            fileList = modelService.generateProjectStructure(prd, stackBackend, stackFrontend, stackDb);
                            log(taskId, "info", "Generated " + fileList.size() + " files in plan.");
                            break;
                            
                        case "codegen_backend":
                            generateFiles(taskId, prd, fullStack, fileList, "backend");
                            break;
                            
                        case "codegen_frontend":
                            generateFiles(taskId, prd, fullStack, fileList, "frontend");
                            break;
                            
                        case "sql_generate":
                            generateFiles(taskId, prd, fullStack, fileList, "database");
                            // Also try to match root files like Dockerfile
                            generateFiles(taskId, prd, fullStack, fileList, "Dockerfile");
                            generateFiles(taskId, prd, fullStack, fileList, "docker-compose");
                            generateFiles(taskId, prd, fullStack, fileList, "README.md");
                            break;
                            
                        case "package":
                            log(taskId, "info", "Packaging artifacts...");
                            String zipPath = packageArtifacts(taskId);
                            saveArtifact(task, zipPath);
                            break;
                    }
                } catch (Exception e) {
                    log(taskId, "error", "Step failed: " + e.getMessage());
                    throw e;
                }

                step.setStatus("finished");
                step.setProgress(100);
                step.setFinishedAt(LocalDateTime.now());
                step.setUpdatedAt(LocalDateTime.now());
                taskStepRepository.save(step);
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

    private void generateFiles(String taskId, String prd, String stack, List<String> fileList, String keyword) {
        for (String filePath : fileList) {
            if (filePath.contains(keyword) || (keyword.equals("database") && filePath.endsWith(".sql"))) {
                log(taskId, "info", "Generating file: " + filePath);
                String content = modelService.generateFileContent(prd, filePath, stack);
                saveFile(taskId, filePath, content);
            }
        }
    }

    private void saveFile(String taskId, String filePath, String content) {
        try {
            Path path = Paths.get("/tmp/smartark/" + taskId + "/" + filePath);
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Failed to save file " + filePath, e);
            log(taskId, "error", "Failed to save file: " + filePath);
        }
    }

    private String packageArtifacts(String taskId) throws IOException {
        Path sourceDir = Paths.get("/tmp/smartark/" + taskId);
        Path zipPath = Paths.get("/tmp/smartark/" + taskId + ".zip");
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            Files.walk(sourceDir)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString());
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        logger.error("Error adding file to zip", e);
                    }
                });
        }
        return "file://" + zipPath.toString();
    }

    private void saveArtifact(TaskEntity task, String storageUrl) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setTaskId(task.getId());
        artifact.setProjectId(task.getProjectId());
        artifact.setArtifactType("zip");
        artifact.setStorageUrl(storageUrl);
        artifact.setSizeBytes(new File(storageUrl.substring(7)).length());
        artifact.setCreatedAt(LocalDateTime.now());
        artifactRepository.save(artifact);
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
