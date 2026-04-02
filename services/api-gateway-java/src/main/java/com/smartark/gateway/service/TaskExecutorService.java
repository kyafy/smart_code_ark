package com.smartark.gateway.service;

import com.smartark.gateway.agent.AgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(TaskExecutorService.class);
    
    private final AgentOrchestrator agentOrchestrator;
    private final DeepAgentExecutorService deepAgentExecutorService;
    private final TaskExecutionModeResolver taskExecutionModeResolver;

    public TaskExecutorService(AgentOrchestrator agentOrchestrator,
                               DeepAgentExecutorService deepAgentExecutorService,
                               TaskExecutionModeResolver taskExecutionModeResolver) {
        this.agentOrchestrator = agentOrchestrator;
        this.deepAgentExecutorService = deepAgentExecutorService;
        this.taskExecutionModeResolver = taskExecutionModeResolver;
    }

    @Async
    public void executeTask(String taskId) {
        TaskExecutionModeResolver.TaskExecutionDecision decision = taskExecutionModeResolver.resolveByTaskId(taskId);
        logger.info("Task executor dispatch: taskId={}, configuredMode={}, selectedMode={}, reason={}",
                taskId, decision.configuredMode(), decision.selectedMode(), decision.reason());

        if (decision.isDeepAgentSelected()) {
            deepAgentExecutorService.run(taskId, decision);
            return;
        }
        agentOrchestrator.run(taskId);
    }

    public TaskExecutionModeResolver.TaskExecutionDecision getExecutionDecision(String taskId) {
        return taskExecutionModeResolver.resolveByTaskId(taskId);
    }
}
