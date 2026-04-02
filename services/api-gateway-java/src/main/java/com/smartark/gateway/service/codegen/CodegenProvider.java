package com.smartark.gateway.service.codegen;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.TemplateRepoService;

public interface CodegenProvider {
    String providerKey();

    CodegenRenderResult tryRender(
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection,
            String codegenEngine
    );
}

