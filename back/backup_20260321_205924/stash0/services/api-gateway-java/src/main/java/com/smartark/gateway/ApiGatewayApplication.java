package com.smartark.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ApiGatewayApplication {
    public static void main(String[] args) {
        loadModelEnvFromDotEnv();
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    private static void loadModelEnvFromDotEnv() {
        for (Path path : candidateDotEnvPaths()) {
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                Map<String, String> values = parseDotEnv(path);
                applyIfMissing("MODEL_BASE_URL", values.get("MODEL_BASE_URL"));
                applyIfMissing("MODEL_API_KEY", values.get("MODEL_API_KEY"));
                applyIfMissing("MODEL_MOCK_ENABLED", values.get("MODEL_MOCK_ENABLED"));
                applyIfMissing("CHAT_MODEL", values.get("CHAT_MODEL"));
                applyIfMissing("CODE_MODEL", values.get("CODE_MODEL"));
                return;
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private static List<Path> candidateDotEnvPaths() {
        return List.of(
                Paths.get(".env"),
                Paths.get("../.env"),
                Paths.get("../../.env")
        );
    }

    private static Map<String, String> parseDotEnv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).trim();
            }
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private static void applyIfMissing(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (System.getProperty(key) != null && !System.getProperty(key).isBlank()) {
            return;
        }
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return;
        }
        System.setProperty(key, value);
    }
}
