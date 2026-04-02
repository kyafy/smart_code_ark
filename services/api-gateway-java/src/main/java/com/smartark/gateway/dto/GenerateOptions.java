package com.smartark.gateway.dto;

public record GenerateOptions(
        String deliveryLevel,
        String templateId,
        String codegenEngine,
        String deployMode,
        String deployEnv,
        Boolean strictDelivery,
        Boolean enablePreview,
        Boolean enableAutoRepair,
        Boolean autoBuildImage,
        Boolean autoPushImage,
        Boolean autoDeployTarget
) {
    public GenerateOptions {
        deliveryLevel = normalizeDeliveryLevel(deliveryLevel);
        codegenEngine = normalizeCodegenEngine(codegenEngine);
        deployMode = normalizeDeployMode(deployMode);
        deployEnv = normalizeDeployEnv(deployEnv);
        strictDelivery = strictDelivery != null ? strictDelivery : Boolean.FALSE;
        enablePreview = enablePreview != null ? enablePreview : Boolean.TRUE;
        enableAutoRepair = enableAutoRepair != null ? enableAutoRepair : Boolean.TRUE;
        autoBuildImage = autoBuildImage != null ? autoBuildImage : Boolean.FALSE;
        autoPushImage = autoPushImage != null ? autoPushImage : Boolean.FALSE;
        autoDeployTarget = autoDeployTarget != null ? autoDeployTarget : Boolean.FALSE;
    }

    public static GenerateOptions defaultOptions() {
        return new GenerateOptions(
                "draft",
                null,
                "llm",
                "none",
                "local",
                Boolean.FALSE,
                Boolean.TRUE,
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.FALSE,
                Boolean.FALSE
        );
    }

    public boolean isDraft() {
        return "draft".equals(deliveryLevel);
    }

    public boolean requiresTemplate() {
        return "validated".equals(deliveryLevel) || "deliverable".equals(deliveryLevel);
    }

    public boolean needsReleasePipeline() {
        if (!"none".equals(deployMode)) {
            return true;
        }
        return Boolean.TRUE.equals(autoBuildImage)
                || Boolean.TRUE.equals(autoPushImage)
                || Boolean.TRUE.equals(autoDeployTarget);
    }

    public static String normalizeDeliveryLevel(String value) {
        if (value == null || value.isBlank()) {
            return "draft";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "draft", "validated", "deliverable" -> normalized;
            default -> "draft";
        };
    }

    public static String normalizeCodegenEngine(String value) {
        if (value == null || value.isBlank()) {
            return "llm";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "llm", "jeecg_rule", "hybrid", "internal_service" -> normalized;
            default -> "llm";
        };
    }

    public static String normalizeDeployMode(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "none", "compose", "k8s" -> normalized;
            default -> "none";
        };
    }

    public static String normalizeDeployEnv(String value) {
        if (value == null || value.isBlank()) {
            return "local";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "local", "test", "staging", "prod" -> normalized;
            default -> "local";
        };
    }
}
