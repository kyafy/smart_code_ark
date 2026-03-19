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

    public TaskExecutorService(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @Async
    public void executeTask(String taskId) {
        logger.info("Delegating task execution to AgentOrchestrator for task: {}", taskId);
        agentOrchestrator.run(taskId);
    }
}

