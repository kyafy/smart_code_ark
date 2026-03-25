package com.smartark.gateway.dto;

import java.util.List;

public record DeliveryManifestResult(
        String stack,
        List<String> entrypoints,
        List<String> services,
        List<String> runCommands
) {
}
