package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.StepMemoryService;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementAnalyzeStepTest {

    @Mock
    private ModelService modelService;

    @Mock
    private StepMemoryService stepMemoryService;

    @TempDir
    Path tempDir;

    @Test
    void execute_fallbackWhenModelStructureFailed() throws Exception {
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService());
        AgentExecutionContext context = buildContext();

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成项目结构失败"));

        step.execute(context);

        assertFalse(context.getFileList().isEmpty());
        assertTrue(context.getFileList().stream().anyMatch(p -> p.endsWith("README.md")));
        assertFalse(context.getFilePlan().isEmpty());
    }

    @Test
    void execute_sanitizeUnsafePaths() throws Exception {
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService());
        AgentExecutionContext context = buildContext();

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("", "   ", "/etc/passwd", "../hack.sh", "backend\\src\\main.ts", "frontend/package.json"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/src/main.ts"));
        assertTrue(context.getFileList().contains("frontend/package.json"));
        assertTrue(context.getFileList().contains("backend/pom.xml"));
        assertTrue(context.getFileList().contains("backend/mvnw"));
        assertTrue(context.getFileList().contains("backend/mvnw.cmd"));
        assertTrue(context.getFileList().contains("docs/deploy.md"));
        assertTrue(context.getFileList().contains("scripts/deploy.sh"));
        assertTrue(context.getFileList().contains("scripts/start.sh"));
        assertFalse(context.getFileList().stream().anyMatch(p -> p.startsWith("/") || p.contains("..")));
    }

    @Test
    void execute_shouldCorrectiveRetryWhenCriticalFilesMissing() throws Exception {
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService());
        AgentExecutionContext context = buildContext();

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("frontend/package.json"))
                .thenReturn(List.of(
                        "backend/pom.xml",
                        "backend/mvnw",
                        "backend/src/main/java/com/example/DemoApplication.java",
                        "backend/src/main/resources/application.yml",
                        "frontend/package.json",
                        "frontend/src/main.ts",
                        "frontend/src/App.vue",
                        "database/schema.sql",
                        "README.md",
                        "docker-compose.yml",
                        "scripts/start.sh",
                        "scripts/deploy.sh",
                        "docs/deploy.md"
                ));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/pom.xml"));
        assertTrue(context.getFileList().contains("docker-compose.yml"));
        assertTrue(context.getFileList().contains("scripts/start.sh"));
    }

    @Test
    void execute_mergesTemplateFilesAndMaterializesWorkspace() throws Exception {
        TemplateRepoService templateRepoService = templateRepoService();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService);
        AgentExecutionContext context = buildContext();
        context.setWorkspaceDir(tempDir.resolve("workspace"));

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("backend/src/main/java/com/example/order/OrderController.java"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/pom.xml"));
        assertTrue(context.getFilePlan().stream().anyMatch(item -> "template_repo:springboot-vue3-mysql".equals(item.getReason())));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("backend/pom.xml")));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("frontend/package.json")));
    }

    @Test
    void execute_shouldPreferExplicitTemplateId() throws Exception {
        TemplateRepoService templateRepoService = templateRepoService();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService);
        AgentExecutionContext context = buildContext();
        context.setWorkspaceDir(tempDir.resolve("workspace-explicit-template"));
        context.getTask().setTemplateId("nextjs-mysql");

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("app/page.tsx"));

        step.execute(context);

        assertTrue(context.getFilePlan().stream().anyMatch(item -> "template_repo:nextjs-mysql".equals(item.getReason())));
        assertTrue(context.getFileList().contains("package.json"));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("package.json")));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("next.config.ts")));
    }

    @Test
    void execute_shouldPlanFastapiTemplateFiles() throws Exception {
        TemplateRepoService templateRepoService = templateRepoService();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService);
        AgentExecutionContext context = buildContext();
        context.setWorkspaceDir(tempDir.resolve("workspace-fastapi"));

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"title\":\"Python Demo\",\"prd\":\"test\",\"stack\":{\"backend\":\"fastapi\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("backend/app/main.py", "frontend/src/App.vue"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/requirements.txt"));
        assertTrue(context.getFileList().contains("backend/app/main.py"));
        assertTrue(context.getFilePlan().stream().anyMatch(item -> "template_repo:fastapi-vue3-mysql".equals(item.getReason())));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("backend/app/main.py")));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("frontend/package.json")));
    }

    @Test
    void execute_shouldPlanFastapiNextjsTemplateFiles() throws Exception {
        TemplateRepoService templateRepoService = templateRepoService();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService);
        AgentExecutionContext context = buildContext();
        context.setWorkspaceDir(tempDir.resolve("workspace-fastapi-next"));

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"title\":\"Portal Demo\",\"prd\":\"test\",\"stack\":{\"backend\":\"fastapi\",\"frontend\":\"nextjs\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("backend/app/main.py", "frontend/app/page.tsx"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/requirements.txt"));
        assertTrue(context.getFileList().contains("frontend/package.json"));
        assertTrue(context.getFileList().contains("frontend/app/page.tsx"));
        assertTrue(context.getFilePlan().stream().anyMatch(item -> "template_repo:fastapi-nextjs-mysql".equals(item.getReason())));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("frontend/next.config.ts")));
    }

    @Test
    void execute_shouldPlanDjangoTemplateFiles() throws Exception {
        TemplateRepoService templateRepoService = templateRepoService();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper(), stepMemoryService, templateRepoService);
        AgentExecutionContext context = buildContext();
        context.setWorkspaceDir(tempDir.resolve("workspace-django"));

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"title\":\"Cms Demo\",\"prd\":\"test\",\"stack\":{\"backend\":\"django\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("backend/manage.py", "frontend/src/App.vue"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/manage.py"));
        assertTrue(context.getFileList().contains("backend/config/settings.py"));
        assertTrue(context.getFilePlan().stream().anyMatch(item -> "template_repo:django-vue3-mysql".equals(item.getReason())));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("backend/manage.py")));
        assertTrue(Files.exists(context.getWorkspaceDir().resolve("frontend/package.json")));
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-r");
        task.setProjectId("project-r");
        context.setTask(task);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"title\":\"Demo Project\",\"prd\":\"test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);
        context.setInstructions("{}");
        context.setWorkspaceDir(tempDir.resolve("default-workspace"));
        return context;
    }

    private TemplateRepoService templateRepoService() throws Exception {
        Path repoRoot = tempDir.resolve("template-repo");
        Files.createDirectories(repoRoot.resolve("templates/springboot-vue3-mysql/backend"));
        Files.createDirectories(repoRoot.resolve("templates/springboot-vue3-mysql/frontend"));
        Files.createDirectories(repoRoot.resolve("templates/fastapi-vue3-mysql/backend/app"));
        Files.createDirectories(repoRoot.resolve("templates/fastapi-vue3-mysql/frontend"));
        Files.createDirectories(repoRoot.resolve("templates/fastapi-nextjs-mysql/backend/app"));
        Files.createDirectories(repoRoot.resolve("templates/fastapi-nextjs-mysql/frontend/app"));
        Files.createDirectories(repoRoot.resolve("templates/django-vue3-mysql/backend/config"));
        Files.createDirectories(repoRoot.resolve("templates/django-vue3-mysql/frontend"));
        Files.createDirectories(repoRoot.resolve("templates/nextjs-mysql/app"));
        Files.writeString(
                repoRoot.resolve("catalog.json"),
                """
                {
                  "version": "1.0.0",
                  "templates": [
                    {
                      "key": "springboot-vue3-mysql",
                      "name": "Spring Boot + Vue 3 + MySQL",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "nextjs-mysql",
                      "name": "Next.js + MySQL",
                      "paths": {
                        "app": "."
                      }
                    },
                    {
                      "key": "fastapi-vue3-mysql",
                      "name": "FastAPI + Vue 3 + MySQL",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "fastapi-nextjs-mysql",
                      "name": "FastAPI + Next.js + MySQL",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "django-vue3-mysql",
                      "name": "Django + Vue 3 + MySQL",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    }
                  ]
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/backend/pom.xml"), "<project>__PROJECT_NAME__</project>");
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/frontend/package.json"), "{\"name\":\"__PROJECT_NAME__\"}");
        Files.writeString(
                repoRoot.resolve("templates/springboot-vue3-mysql/template.json"),
                """
                {
                  "key": "springboot-vue3-mysql",
                  "name": "Spring Boot + Vue 3 + MySQL",
                  "stack": {
                    "backend": "Spring Boot 3",
                    "frontend": "Vue 3 + Vite",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "docker": "docker compose up --build"
                  }
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/nextjs-mysql/package.json"), "{\"name\":\"__PROJECT_NAME__\"}");
        Files.writeString(repoRoot.resolve("templates/nextjs-mysql/next.config.ts"), "export default {}");
        Files.writeString(
                repoRoot.resolve("templates/nextjs-mysql/template.json"),
                """
                {
                  "key": "nextjs-mysql",
                  "name": "Next.js + MySQL",
                  "stack": {
                    "app": "Next.js 15 + React",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "development": "npm run dev"
                  }
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/fastapi-vue3-mysql/backend/requirements.txt"), "fastapi==0.116.0");
        Files.writeString(repoRoot.resolve("templates/fastapi-vue3-mysql/backend/app/main.py"), "app = 'fastapi'");
        Files.writeString(repoRoot.resolve("templates/fastapi-vue3-mysql/frontend/package.json"), "{\"name\":\"__PROJECT_NAME__\"}");
        Files.writeString(
                repoRoot.resolve("templates/fastapi-vue3-mysql/template.json"),
                """
                {
                  "key": "fastapi-vue3-mysql",
                  "name": "FastAPI + Vue 3 + MySQL",
                  "stack": {
                    "backend": "FastAPI + SQLAlchemy + MySQL",
                    "frontend": "Vue 3 + Vite",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "development": "uvicorn app.main:app --reload"
                  }
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/fastapi-nextjs-mysql/backend/requirements.txt"), "fastapi==0.116.0");
        Files.writeString(repoRoot.resolve("templates/fastapi-nextjs-mysql/backend/app/main.py"), "app = 'fastapi'");
        Files.writeString(repoRoot.resolve("templates/fastapi-nextjs-mysql/frontend/package.json"), "{\"name\":\"__PROJECT_NAME__-web\"}");
        Files.writeString(repoRoot.resolve("templates/fastapi-nextjs-mysql/frontend/next.config.ts"), "export default {}");
        Files.writeString(repoRoot.resolve("templates/fastapi-nextjs-mysql/frontend/app/page.tsx"), "export default function Page() { return null; }");
        Files.writeString(
                repoRoot.resolve("templates/fastapi-nextjs-mysql/template.json"),
                """
                {
                  "key": "fastapi-nextjs-mysql",
                  "name": "FastAPI + Next.js + MySQL",
                  "stack": {
                    "backend": "FastAPI + SQLAlchemy + MySQL",
                    "frontend": "Next.js 15 + React 19",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "development": "cd frontend && npm run dev"
                  }
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/django-vue3-mysql/backend/manage.py"), "print('manage')");
        Files.writeString(repoRoot.resolve("templates/django-vue3-mysql/backend/config/settings.py"), "APP_NAME='__PROJECT_NAME__'");
        Files.writeString(repoRoot.resolve("templates/django-vue3-mysql/frontend/package.json"), "{\"name\":\"__PROJECT_NAME__-frontend\"}");
        Files.writeString(
                repoRoot.resolve("templates/django-vue3-mysql/template.json"),
                """
                {
                  "key": "django-vue3-mysql",
                  "name": "Django + Vue 3 + MySQL",
                  "stack": {
                    "backend": "Django 5 + MySQL",
                    "frontend": "Vue 3 + Vite",
                    "database": "MySQL 8"
                  },
                  "run": {
                    "development": "cd backend && python manage.py runserver"
                  }
                }
                """
        );
        return new TemplateRepoService(new ObjectMapper(), repoRoot.toString());
    }
}
