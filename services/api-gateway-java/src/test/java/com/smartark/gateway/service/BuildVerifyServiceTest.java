package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuildVerifyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void verify_shouldSkipForDraftDeliveryLevel() throws Exception {
        BuildVerifyService service = buildService();
        ReflectionTestUtils.setField(service, "buildVerifyEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "composeCheckEnabled", true);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);

        TaskEntity task = new TaskEntity();
        task.setId("task-draft");
        task.setDeliveryLevelRequested("draft");

        BuildVerifyService.BuildVerifyBundle result = service.verify(task, tempDir);

        assertThat(result.buildReport().skipped()).isTrue();
        assertThat(result.buildReport().passed()).isTrue();
        assertThat(result.deliveryReport().deliveryLevelActual()).isEqualTo("draft");
        assertThat(result.deliveryReport().status()).isEqualTo("passed");
        assertThat(Files.exists(tempDir.resolve("build_verify_report.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("delivery_report.json"))).isTrue();
    }

    @Test
    void verify_shouldFailWhenValidatedAndBuildVerifyDisabled() throws Exception {
        BuildVerifyService service = buildService();
        ReflectionTestUtils.setField(service, "buildVerifyEnabled", false);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "composeCheckEnabled", true);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);

        TaskEntity task = new TaskEntity();
        task.setId("task-validated");
        task.setDeliveryLevelRequested("validated");

        BuildVerifyService.BuildVerifyBundle result = service.verify(task, tempDir);

        assertThat(result.buildReport().passed()).isFalse();
        assertThat(result.buildReport().blockingIssues())
                .extracting(issue -> issue.code())
                .contains("build_verify_disabled");
        assertThat(result.deliveryReport().deliveryLevelActual()).isEqualTo("draft");
        assertThat(result.deliveryReport().status()).isEqualTo("failed");
    }

    @Test
    void verify_shouldDetectFrontendMobileAndBuildH5WhenCommandExecutionDisabled() throws Exception {
        TemplateRepoService templateRepoService = buildTemplateRepoService();
        BuildVerifyService service = new BuildVerifyService(new ObjectMapper(), templateRepoService);
        ReflectionTestUtils.setField(service, "buildVerifyEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", false);
        ReflectionTestUtils.setField(service, "composeCheckEnabled", false);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);

        Path workspace = tempDir.resolve("uniapp-workspace");
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend-mobile"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("frontend-mobile/package.json"), """
                {
                  "scripts": {
                    "dev:h5": "vite",
                    "build:h5": "vite build"
                  }
                }
                """);

        TaskEntity task = new TaskEntity();
        task.setId("task-uniapp");
        task.setTemplateId("uniapp-springboot-api");
        task.setDeliveryLevelRequested("validated");

        BuildVerifyService.BuildVerifyBundle result = service.verify(task, workspace);

        assertThat(result.buildReport().commands())
                .extracting(command -> command.name())
                .contains("frontend-npm-build-h5");
        assertThat(result.buildReport().commands())
                .filteredOn(command -> "frontend-npm-build-h5".equals(command.name()))
                .extracting(command -> command.workdir())
                .contains("frontend-mobile");
    }

    @Test
    void verify_shouldSupportNextJsRootTemplateCommandsWhenCommandExecutionDisabled() throws Exception {
        TemplateRepoService templateRepoService = buildTemplateRepoService();
        BuildVerifyService service = new BuildVerifyService(new ObjectMapper(), templateRepoService);
        ReflectionTestUtils.setField(service, "buildVerifyEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", false);
        ReflectionTestUtils.setField(service, "composeCheckEnabled", false);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);

        Path workspace = tempDir.resolve("nextjs-workspace");
        Files.createDirectories(workspace.resolve("app"));
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "scripts": {
                    "dev": "next dev",
                    "build": "next build",
                    "prisma:generate": "prisma generate"
                  }
                }
                """);
        Files.writeString(workspace.resolve("next.config.ts"), "export default {}");

        TaskEntity task = new TaskEntity();
        task.setId("task-nextjs");
        task.setTemplateId("nextjs-mysql");
        task.setDeliveryLevelRequested("validated");

        BuildVerifyService.BuildVerifyBundle result = service.verify(task, workspace);

        assertThat(result.buildReport().commands())
                .extracting(command -> command.name())
                .contains("app-npm-prisma-generate", "app-npm-build");
        assertThat(result.buildReport().commands())
                .filteredOn(command -> "app-npm-build".equals(command.name()))
                .extracting(command -> command.workdir())
                .contains(".");
    }

    private BuildVerifyService buildService() throws Exception {
        return new BuildVerifyService(new ObjectMapper(), buildTemplateRepoService());
    }

    private TemplateRepoService buildTemplateRepoService() throws Exception {
        Path repoRoot = tempDir.resolve("template-repo");
        Files.createDirectories(repoRoot.resolve("templates/uniapp-springboot-api"));
        Files.createDirectories(repoRoot.resolve("templates/nextjs-mysql"));
        Files.writeString(
                repoRoot.resolve("catalog.json"),
                """
                {
                  "version": "1.0.0",
                  "templates": [
                    {
                      "key": "uniapp-springboot-api",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend-mobile"
                      }
                    },
                    {
                      "key": "nextjs-mysql",
                      "paths": {
                        "app": "."
                      }
                    }
                  ]
                }
                """
        );
        Files.writeString(
                repoRoot.resolve("templates/uniapp-springboot-api/template.json"),
                """
                {
                  "key": "uniapp-springboot-api",
                  "name": "UniApp + Spring Boot API",
                  "stack": {
                    "frontend": "UniApp Vue 3 + Vite",
                    "backend": "Spring Boot 3 REST API"
                  },
                  "run": {
                    "backend": "cd backend && mvn spring-boot:run",
                    "frontend": "cd frontend-mobile && npm install && npm run dev:h5"
                  }
                }
                """
        );
        Files.writeString(
                repoRoot.resolve("templates/nextjs-mysql/template.json"),
                """
                {
                  "key": "nextjs-mysql",
                  "name": "Next.js + MySQL",
                  "stack": {
                    "app": "Next.js 15 + React 19 + Prisma",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "development": "npm install && npm run prisma:generate && npm run db:push && npm run dev"
                  }
                }
                """
        );
        return new TemplateRepoService(new ObjectMapper(), repoRoot.toString());
    }
}
