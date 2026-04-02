package com.smartark.gateway.service;

import com.smartark.gateway.agent.AgentOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutorServiceTest {

    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private DeepAgentExecutorService deepAgentExecutorService;
    @Mock
    private TaskExecutionModeResolver taskExecutionModeResolver;

    private TaskExecutorService taskExecutorService;

    @BeforeEach
    void setUp() {
        taskExecutorService = new TaskExecutorService(
                agentOrchestrator,
                deepAgentExecutorService,
                taskExecutionModeResolver
        );
    }

    @Test
    void executeTask_shouldRouteToLegacyOrchestrator() {
        TaskExecutionModeResolver.TaskExecutionDecision decision =
                new TaskExecutionModeResolver.TaskExecutionDecision(
                        "task-legacy", "generate", "legacy", "legacy",
                        "forced_legacy", 12, 0, true
                );
        when(taskExecutionModeResolver.resolveByTaskId("task-legacy")).thenReturn(decision);

        taskExecutorService.executeTask("task-legacy");

        verify(agentOrchestrator).run("task-legacy");
        verify(deepAgentExecutorService, never()).run("task-legacy", decision);
    }

    @Test
    void executeTask_shouldRouteToDeepagentExecutor() {
        TaskExecutionModeResolver.TaskExecutionDecision decision =
                new TaskExecutionModeResolver.TaskExecutionDecision(
                        "task-da", "generate", "deepagent", "deepagent",
                        "forced_deepagent", 12, 100, true
                );
        when(taskExecutionModeResolver.resolveByTaskId("task-da")).thenReturn(decision);

        taskExecutorService.executeTask("task-da");

        verify(deepAgentExecutorService).run("task-da", decision);
        verify(agentOrchestrator, never()).run("task-da");
    }
}
