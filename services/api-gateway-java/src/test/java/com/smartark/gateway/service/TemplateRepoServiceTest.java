package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRepoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveTemplate_matchesSpringbootVueMysql() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplate("springboot", "vue3", "mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("springboot-vue3-mysql");
        assertThat(selection.get().frontendRoot()).isEqualTo("frontend");
        assertThat(selection.get().metadata().run()).containsKey("docker");
    }

    @Test
    void resolveTemplate_matchesNextjsMysql() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplate("nextjs", "nextjs", "mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("nextjs-mysql");
        assertThat(selection.get().frontendRoot()).isEqualTo(".");
        assertThat(selection.get().metadata().stack()).containsKey("app");
    }

    @Test
    void resolveTemplate_matchesFastapiVueMysql() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplate("fastapi", "vue3", "mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("fastapi-vue3-mysql");
        assertThat(selection.get().backendRoot()).isEqualTo("backend");
        assertThat(selection.get().frontendRoot()).isEqualTo("frontend");
        assertThat(selection.get().metadata().stack()).containsEntry("backend", "FastAPI + SQLAlchemy + MySQL");
    }

    @Test
    void resolveTemplate_matchesFastapiNextjsMysql() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplate("fastapi", "nextjs", "mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("fastapi-nextjs-mysql");
        assertThat(selection.get().backendRoot()).isEqualTo("backend");
        assertThat(selection.get().frontendRoot()).isEqualTo("frontend");
        assertThat(selection.get().metadata().stack()).containsEntry("frontend", "Next.js 15 + React 19");
    }

    @Test
    void resolveTemplate_matchesDjangoVueMysql() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplate("django", "vue3", "mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("django-vue3-mysql");
        assertThat(selection.get().backendRoot()).isEqualTo("backend");
        assertThat(selection.get().frontendRoot()).isEqualTo("frontend");
        assertThat(selection.get().metadata().stack()).containsEntry("backend", "Django 5 + MySQL");
    }

    @Test
    void resolveTemplateById_shouldUseExplicitTemplate() throws Exception {
        TemplateRepoService service = buildService();

        Optional<TemplateRepoService.TemplateSelection> selection = service.resolveTemplateById("nextjs-mysql");

        assertThat(selection).isPresent();
        assertThat(selection.get().templateKey()).isEqualTo("nextjs-mysql");
        assertThat(selection.get().backendRoot()).isEqualTo(".");
        assertThat(selection.get().frontendRoot()).isEqualTo(".");
        assertThat(selection.get().metadata().name()).isEqualTo("Next.js + MySQL");
    }

    @Test
    void materializeTemplate_replacesPlaceholders() throws Exception {
        TemplateRepoService service = buildService();

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-template");
        task.setProjectId("project-template");
        context.setTask(task);
        context.setWorkspaceDir(tempDir.resolve("workspace"));

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("""
                {
                  "title": "Order Center",
                  "stack": {
                    "backend": "springboot",
                    "frontend": "vue3",
                    "db": "mysql"
                  }
                }
                """);
        context.setSpec(spec);

        service.materializeTemplate(context);

        assertThat(Files.readString(context.getWorkspaceDir().resolve("backend/pom.xml"))).contains("order-center");
        assertThat(Files.readString(context.getWorkspaceDir().resolve("README.md"))).contains("Order Center");
    }

    private TemplateRepoService buildService() throws Exception {
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
                      "category": "fullstack",
                      "description": "Spring Boot and Vue starter.",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "nextjs-mysql",
                      "name": "Next.js + MySQL",
                      "category": "fullstack",
                      "description": "Next.js App Router starter.",
                      "paths": {
                        "app": "."
                      }
                    },
                    {
                      "key": "fastapi-vue3-mysql",
                      "name": "FastAPI + Vue 3 + MySQL",
                      "category": "fullstack",
                      "description": "Separated Python backend and Vue starter.",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "fastapi-nextjs-mysql",
                      "name": "FastAPI + Next.js + MySQL",
                      "category": "fullstack",
                      "description": "Separated Python backend and Next.js starter.",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    },
                    {
                      "key": "django-vue3-mysql",
                      "name": "Django + Vue 3 + MySQL",
                      "category": "fullstack",
                      "description": "Separated Django backend and Vue starter.",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    }
                  ]
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/README.md"), "# __DISPLAY_NAME__");
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/backend/pom.xml"), "<artifactId>__PROJECT_NAME__</artifactId>");
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
