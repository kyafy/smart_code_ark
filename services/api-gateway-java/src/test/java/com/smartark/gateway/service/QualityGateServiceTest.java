package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QualityGateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void evaluate_shouldPass_whenWorkspaceIsValid() throws Exception {
        QualityGateService service = new QualityGateService(new ObjectMapper());
        Files.writeString(tempDir.resolve("docker-compose.yml"), """
                services:
                  backend:
                    build:
                      context: ./backend
                  frontend:
                    build:
                      context: ./frontend
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("backend"));
        Files.createDirectories(tempDir.resolve("frontend"));
        Files.createDirectories(tempDir.resolve("scripts"));
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("scripts/start.sh"), "docker compose up --build -d", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("docs/deploy.md"), "Use docker compose up --build -d", StandardCharsets.UTF_8);

        QualityGateService.QualityGateResult result = service.evaluate(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.failedRules()).isEmpty();
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void evaluate_shouldFail_whenMissingCriticalFiles() throws Exception {
        QualityGateService service = new QualityGateService(new ObjectMapper());
        Files.writeString(tempDir.resolve("docker-compose.yml"), "services: {}", StandardCharsets.UTF_8);

        QualityGateService.QualityGateResult result = service.evaluate(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.failedRules()).anyMatch(rule -> rule.contains("missing_start_script"));
        assertThat(result.score()).isLessThan(1.0);
    }

    @Test
    void autoFix_shouldRepairWorkspaceAndPassOnReevaluate() throws Exception {
        QualityGateService service = new QualityGateService(new ObjectMapper());
        Files.createDirectories(tempDir.resolve("backend"));
        Files.createDirectories(tempDir.resolve("frontend"));
        Files.writeString(tempDir.resolve("docker-compose.yml"), """
                services:
                  backend:
                    build:
                      context: ./missing-backend
                  frontend:
                    build:
                      context: ./missing-frontend
                """, StandardCharsets.UTF_8);

        QualityGateService.QualityGateResult first = service.evaluate(tempDir);
        assertThat(first.passed()).isFalse();

        var fixedActions = service.autoFix(tempDir, first.failedRules());
        QualityGateService.QualityGateResult second = service.evaluate(tempDir);

        assertThat(fixedActions).isNotEmpty();
        assertThat(second.passed()).isTrue();
        assertThat(second.failedRules()).isEmpty();
    }
}
