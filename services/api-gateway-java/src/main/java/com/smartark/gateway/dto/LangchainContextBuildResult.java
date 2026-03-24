package com.smartark.gateway.dto;

import java.util.List;

public record LangchainContextBuildResult(
        String contextPack,
        List<String> sources,
        Integer totalItems
) {
}
