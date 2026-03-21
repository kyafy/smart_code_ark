package com.smartark.gateway.agent;

public interface AgentStep {
    String getStepCode();
    void execute(AgentExecutionContext context) throws Exception;
}
