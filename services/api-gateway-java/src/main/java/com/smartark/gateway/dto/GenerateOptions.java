package com.smartark.gateway.dto;

public record GenerateOptions(
        String deliveryLevel,
        String templateId,
        Boolean strictDelivery,
        Boolean enablePreview,
        Boolean enableAutoRepair
) {
    public GenerateOptions {
        deliveryLevel = normalizeDeliveryLevel(deliveryLevel);
        strictDelivery = strictDelivery != null ? strictDelivery : Boolean.FALSE;
        enablePreview = enablePreview != null ? enablePreview : Boolean.TRUE;
        enableAutoRepair = enableAutoRepair != null ? enableAutoRepair : Boolean.TRUE;
    }

    public static GenerateOptions defaultOptions() {
        return new GenerateOptions("draft", null, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
    }

    public boolean isDraft() {
        return "draft".equals(deliveryLevel);
    }

    public boolean requiresTemplate() {
        return "validated".equals(deliveryLevel) || "deliverable".equals(deliveryLevel);
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
}
