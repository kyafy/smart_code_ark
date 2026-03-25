package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageStepTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_shouldFillMissingDeliveryFilesAndRepairComposeContext() throws Exception {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageStep step = new PackageStep(artifactRepository, new ObjectMapper());

        Path workspace = tempDir.resolve("task1");
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("docker-compose.yml"),
                "services:\n" +
                        "  backend:\n" +
                        "    build:\n" +
                        "      context: ./missing-backend\n" +
                        "  frontend:\n" +
                        "    build:\n" +
                        "      context: ./missing-frontend\n",
                StandardCharsets.UTF_8
        );

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task1");
        task.setProjectId("p1");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\"}}");
        context.setSpec(spec);

        step.execute(context);

        assertThat(Files.exists(workspace.resolve("scripts/start.sh"))).isTrue();
        assertThat(Files.exists(workspace.resolve("scripts/deploy.sh"))).isTrue();
        assertThat(Files.exists(workspace.resolve("docs/deploy.md"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/mvnw"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/mvnw.cmd"))).isTrue();
        assertThat(Files.exists(workspace.resolve("frontend/package.json"))).isTrue();

        String compose = Files.readString(workspace.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose).contains("context: ./backend");
        assertThat(compose).contains("context: ./frontend");

        ArgumentCaptor<ArtifactEntity> captor = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(artifactRepository, times(1)).save(captor.capture());
        ArtifactEntity artifact = captor.getValue();
        assertThat(artifact.getArtifactType()).isEqualTo("zip");
        assertThat(artifact.getStorageUrl()).startsWith("file://");

        Path zipPath = Path.of(artifact.getStorageUrl().substring(7));
        assertThat(Files.exists(zipPath)).isTrue();
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            var names = zipFile.stream().map(e -> e.getName().replace("\\", "/")).toList();
            assertThat(names).contains("scripts/start.sh");
            assertThat(names).contains("frontend/package.json");
            assertThat(names).contains("backend/mvnw");
        }
    }

    @Test
    void execute_shouldHandleNextjsRootWorkspace() throws Exception {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageStep step = new PackageStep(artifactRepository, new ObjectMapper());

        Path workspace = tempDir.resolve("task-next");
        Files.createDirectories(workspace.resolve("app"));
        Files.writeString(workspace.resolve("package.json"), "{\"name\":\"next-app\"}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("next.config.ts"), "export default {}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("docker-compose.yml"), "services:\n  mysql:\n    image: mysql:8.4\n", StandardCharsets.UTF_8);

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-next");
        task.setProjectId("p-next");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"nextjs\",\"frontend\":\"nextjs\"}}");
        context.setSpec(spec);

        step.execute(context);

        assertThat(Files.exists(workspace.resolve("Dockerfile"))).isTrue();
        assertThat(Files.exists(workspace.resolve("app/layout.tsx"))).isTrue();
        assertThat(Files.exists(workspace.resolve("scripts/start.sh"))).isTrue();
        assertThat(Files.exists(workspace.resolve("index.html"))).isFalse();
        assertThat(Files.exists(workspace.resolve("vite.config.ts"))).isFalse();
    }

    @Test
    void execute_shouldFillFastapiBackendDeliveryFiles() throws Exception {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageStep step = new PackageStep(artifactRepository, new ObjectMapper());

        Path workspace = tempDir.resolve("task-fastapi");
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("frontend/package.json"), "{\"name\":\"frontend\"}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("docker-compose.yml"), "services:\n  mysql:\n    image: mysql:8.4\n", StandardCharsets.UTF_8);

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-fastapi");
        task.setProjectId("p-fastapi");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"fastapi\",\"frontend\":\"vue3\"}}");
        context.setSpec(spec);

        step.execute(context);

        assertThat(Files.exists(workspace.resolve("backend/requirements.txt"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/app/main.py"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/Dockerfile"))).isTrue();
        assertThat(Files.readString(workspace.resolve("backend/Dockerfile"), StandardCharsets.UTF_8))
                .contains("python:3.12-slim")
                .contains("uvicorn");
    }

    @Test
    void execute_shouldHandleNextjsFrontendSubdirectory() throws Exception {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageStep step = new PackageStep(artifactRepository, new ObjectMapper());

        Path workspace = tempDir.resolve("task-fastapi-next");
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("docker-compose.yml"), "services:\n  mysql:\n    image: mysql:8.4\n", StandardCharsets.UTF_8);

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-fastapi-next");
        task.setProjectId("p-fastapi-next");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"fastapi\",\"frontend\":\"nextjs\"}}");
        context.setSpec(spec);

        step.execute(context);

        assertThat(Files.exists(workspace.resolve("frontend/package.json"))).isTrue();
        assertThat(Files.exists(workspace.resolve("frontend/next.config.ts"))).isTrue();
        assertThat(Files.exists(workspace.resolve("frontend/app/layout.tsx"))).isTrue();
        assertThat(Files.exists(workspace.resolve("next.config.ts"))).isFalse();
        assertThat(Files.exists(workspace.resolve("app/layout.tsx"))).isFalse();
    }

    @Test
    void execute_shouldFillDjangoBackendDeliveryFiles() throws Exception {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(artifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PackageStep step = new PackageStep(artifactRepository, new ObjectMapper());

        Path workspace = tempDir.resolve("task-django");
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("frontend/package.json"), "{\"name\":\"frontend\"}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("docker-compose.yml"), "services:\n  mysql:\n    image: mysql:8.4\n", StandardCharsets.UTF_8);

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-django");
        task.setProjectId("p-django");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"django\",\"frontend\":\"vue3\"}}");
        context.setSpec(spec);

        step.execute(context);

        assertThat(Files.exists(workspace.resolve("backend/requirements.txt"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/manage.py"))).isTrue();
        assertThat(Files.exists(workspace.resolve("backend/config/settings.py"))).isTrue();
        assertThat(Files.readString(workspace.resolve("backend/Dockerfile"), StandardCharsets.UTF_8))
                .contains("python manage.py migrate")
                .contains("runserver 0.0.0.0:8000");
    }
}
