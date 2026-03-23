package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ChatMessageEntity;
import com.smartark.gateway.db.entity.ChatSessionEntity;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ChatMessageRepository;
import com.smartark.gateway.db.repo.ChatSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.ProjectConfirmRequest;
import com.smartark.gateway.dto.ProjectConfirmResult;
import com.smartark.gateway.dto.ProjectDetail;
import com.smartark.gateway.dto.ProjectSummary;
import com.smartark.gateway.dto.StackConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.smartark.gateway.dto.TaskSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectSpecRepository projectSpecRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TaskRepository taskRepository;
    private final ModelService modelService;
    private final PreviewLifecycleService previewLifecycleService;
    private final ObjectMapper objectMapper;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectSpecRepository projectSpecRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            TaskRepository taskRepository,
            ModelService modelService,
            PreviewLifecycleService previewLifecycleService,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.taskRepository = taskRepository;
        this.modelService = modelService;
        this.previewLifecycleService = previewLifecycleService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectConfirmResult confirm(ProjectConfirmRequest request) {
        Long userId = requireUserId();
        ChatSessionEntity session = chatSessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该会话");
        }
        if ("deleted".equalsIgnoreCase(session.getStatus())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "会话不存在");
        }

        if (session.getProjectId() != null && !session.getProjectId().isBlank()) {
            ProjectEntity existing = projectRepository.findByIdAndDeletedAtIsNull(session.getProjectId())
                    .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "项目不存在"));
            return new ProjectConfirmResult(existing.getId(), existing.getStatus());
        }

        String projectId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setUserId(userId);
        project.setTitle(session.getTitle());
        String desc = request.description();
        if (desc == null || desc.isBlank()) {
            desc = session.getDescription();
        }
        project.setDescription(desc == null ? "" : desc);
        project.setProjectType(session.getProjectType());
        project.setStackBackend(request.stack().backend());
        project.setStackFrontend(request.stack().frontend());
        project.setStackDb(request.stack().db());
        project.setStatus("confirmed");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectRepository.save(project);

        session.setProjectId(projectId);
        session.setStatus("confirmed");
        session.setUpdatedAt(now);
        chatSessionRepository.save(session);

        try {
            ProjectSpecEntity spec = new ProjectSpecEntity();
            spec.setProjectId(projectId);
            spec.setVersion(1);
            
            spec.setRequirementJson(objectMapper.writeValueAsString(Map.of(
                    "title", project.getTitle(),
                    "description", project.getDescription(),
                    "projectType", project.getProjectType(),
                    "stack", Map.of(
                            "backend", project.getStackBackend(),
                            "frontend", project.getStackFrontend(),
                            "db", project.getStackDb()
                    ),
                    "sessionId", session.getId(),
                    "prd", request.prd() != null ? request.prd() : ""
            )));
            
            spec.setDomainJson(null);
            spec.setApiContractJson(null);
            spec.setCreatedAt(now);
            projectSpecRepository.save(spec);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "Failed to save project spec");
        }

        return new ProjectConfirmResult(projectId, "confirmed");
    }

    public List<ProjectSummary> list() {
        Long userId = requireUserId();
        return projectRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId).stream()
                .map(p -> new ProjectSummary(
                        p.getId(),
                        p.getTitle(),
                        p.getDescription(),
                        p.getStatus(),
                        p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString()
                ))
                .toList();
    }

    public ProjectDetail detail(String projectId) {
        Long userId = requireUserId();
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "项目不存在"));
        if (!userId.equals(project.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该项目");
        }
        String specJson = projectSpecRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ProjectSpecEntity::getRequirementJson)
                .orElse(null);
        StackConfig stack = new StackConfig(
                project.getStackBackend() == null ? "" : project.getStackBackend(),
                project.getStackFrontend() == null ? "" : project.getStackFrontend(),
                project.getStackDb() == null ? "" : project.getStackDb()
        );

        List<TaskSummary> tasks = taskRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(t -> new TaskSummary(
                        t.getId(),
                        t.getProjectId(),
                        t.getTaskType(),
                        t.getStatus(),
                        t.getProgress(),
                        t.getErrorMessage(),
                        t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                        t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null
                ))
                .collect(Collectors.toList());

        List<Map<String, String>> messages = new ArrayList<>();
        if (specJson != null) {
            try {
                String sessionId = objectMapper.readTree(specJson).path("sessionId").asText(null);
                if (sessionId != null && !sessionId.isEmpty()) {
                    messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                            .map(m -> Map.of(
                                    "role", m.getSpeaker(),
                                    "content", m.getMessage()
                            ))
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                // Ignore parse error
            }
        }

        return new ProjectDetail(
                project.getId(),
                project.getTitle(),
                project.getDescription(),
                project.getProjectType(),
                project.getStatus(),
                stack,
                specJson,
                project.getCreatedAt() == null ? null : project.getCreatedAt().toString(),
                project.getUpdatedAt() == null ? null : project.getUpdatedAt().toString(),
                tasks,
                messages
        );
    }

    @Transactional
    public boolean delete(String projectId) {
        Long userId = requireUserId();
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "项目不存在"));
        if (!userId.equals(project.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该项目");
        }

        LocalDateTime now = LocalDateTime.now();
        project.setDeletedAt(now);
        project.setStatus("deleted");
        project.setUpdatedAt(now);
        projectRepository.save(project);

        try {
            previewLifecycleService.cleanupProjectPreviews(projectId);
        } catch (Exception ignored) {
        }

        return true;
    }

    private Long requireUserId() {
        String userId = RequestContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        try {
            return Long.parseLong(userId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
    }
}
