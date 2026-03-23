package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.repo.ChatMessageRepository;
import com.smartark.gateway.db.repo.ChatSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceDeleteTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectSpecRepository projectSpecRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private ModelService modelService;
    @Mock private PreviewLifecycleService previewLifecycleService;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository,
                projectSpecRepository,
                chatSessionRepository,
                chatMessageRepository,
                taskRepository,
                modelService,
                previewLifecycleService,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void delete_shouldSoftDeleteProjectAndCleanupPreviews() {
        RequestContext.setUserId("1");
        ProjectEntity project = buildProject("p1", 1L);

        when(projectRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(project));

        boolean ok = projectService.delete("p1");
        assertThat(ok).isTrue();

        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(projectRepository).save(captor.capture());
        ProjectEntity saved = captor.getValue();
        assertThat(saved.getDeletedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("deleted");

        verify(previewLifecycleService).cleanupProjectPreviews("p1");
    }

    @Test
    void delete_shouldThrowForbiddenWhenUserNotOwner() {
        RequestContext.setUserId("2");
        ProjectEntity project = buildProject("p1", 1L);

        when(projectRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectService.delete("p1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCodes.FORBIDDEN));

        verify(projectRepository, never()).save(any());
        verify(previewLifecycleService, never()).cleanupProjectPreviews(any());
    }

    @Test
    void delete_shouldThrowNotFoundWhenMissing() {
        RequestContext.setUserId("1");

        when(projectRepository.findByIdAndDeletedAtIsNull("p404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete("p404"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCodes.NOT_FOUND));

        verify(projectRepository, never()).save(any());
        verify(previewLifecycleService, never()).cleanupProjectPreviews(any());
    }

    @Test
    void list_shouldUseNonDeletedQuery() {
        RequestContext.setUserId("1");
        when(projectRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(1L))
                .thenReturn(List.of(buildProject("p1", 1L)));

        assertThat(projectService.list()).hasSize(1);
        verify(projectRepository).findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(1L);
    }

    private ProjectEntity buildProject(String id, Long userId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setUserId(userId);
        project.setTitle("t");
        project.setDescription("");
        project.setProjectType("web");
        project.setStatus("confirmed");
        project.setCreatedAt(LocalDateTime.now().minusDays(1));
        project.setUpdatedAt(LocalDateTime.now().minusHours(1));
        project.setDeletedAt(null);
        return project;
    }
}
