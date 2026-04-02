package com.smartark.gateway.service.codegen;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.JeecgCodegenClient;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.stereotype.Service;

@Service
public class JeecgCodegenProvider implements CodegenProvider {
    private final JeecgCodegenClient jeecgCodegenClient;

    public JeecgCodegenProvider(JeecgCodegenClient jeecgCodegenClient) {
        this.jeecgCodegenClient = jeecgCodegenClient;
    }

    @Override
    public String providerKey() {
        return "jeecg";
    }

    @Override
    public CodegenRenderResult tryRender(
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection,
            String codegenEngine
    ) {
        JeecgCodegenClient.JeecgRenderResult result = jeecgCodegenClient.tryRender(context, selection);
        return new CodegenRenderResult(
                result.success(),
                result.invoked(),
                providerKey(),
                result.message(),
                result.files()
        );
    }
}

