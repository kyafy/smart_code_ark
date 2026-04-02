package com.smartark.gateway.service;

import com.smartark.gateway.db.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TaskExecutionModeResolverTest {

    @Mock
    private TaskRepository taskRepository;

    private TaskExecutionModeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TaskExecutionModeResolver(taskRepository);
        ReflectionTestUtils.setField(resolver, "deepAgentSupportedTaskTypes", "generate,modify");
        ReflectionTestUtils.setField(resolver, "executorAbRatio", 0);
        ReflectionTestUtils.setField(resolver, "executorMode", "legacy");
    }

    @Test
    void resolve_shouldUseLegacyWhenConfiguredLegacy() {
        TaskExecutionModeResolver.TaskExecutionDecision decision = resolver.resolve("t1", "generate");

        assertThat(decision.selectedMode()).isEqualTo("legacy");
        assertThat(decision.reason()).isEqualTo("forced_legacy");
    }

    @Test
    void resolve_shouldUseDeepagentWhenConfiguredDeepagentAndEligible() {
        ReflectionTestUtils.setField(resolver, "executorMode", "deepagent");

        TaskExecutionModeResolver.TaskExecutionDecision decision = resolver.resolve("t2", "generate");

        assertThat(decision.selectedMode()).isEqualTo("deepagent");
        assertThat(decision.reason()).isEqualTo("forced_deepagent");
        assertThat(decision.deepAgentEligible()).isTrue();
    }

    @Test
    void resolve_shouldFallbackToLegacyWhenTaskTypeNotEligible() {
        ReflectionTestUtils.setField(resolver, "executorMode", "deepagent");

        TaskExecutionModeResolver.TaskExecutionDecision decision = resolver.resolve("t3", "paper_outline");

        assertThat(decision.selectedMode()).isEqualTo("legacy");
        assertThat(decision.reason()).isEqualTo("task_type_not_supported");
        assertThat(decision.deepAgentEligible()).isFalse();
    }

    @Test
    void resolve_shouldHitAbWhenBucketWithinRatio() {
        ReflectionTestUtils.setField(resolver, "executorMode", "ab");
        ReflectionTestUtils.setField(resolver, "executorAbRatio", 100);

        TaskExecutionModeResolver.TaskExecutionDecision decision = resolver.resolve("t4", "modify");

        assertThat(decision.selectedMode()).isEqualTo("deepagent");
        assertThat(decision.reason()).isEqualTo("ab_hit");
    }

    @Test
    void resolve_shouldMissAbWhenRatioZero() {
        ReflectionTestUtils.setField(resolver, "executorMode", "ab");
        ReflectionTestUtils.setField(resolver, "executorAbRatio", 0);

        TaskExecutionModeResolver.TaskExecutionDecision decision = resolver.resolve("t5", "modify");

        assertThat(decision.selectedMode()).isEqualTo("legacy");
        assertThat(decision.reason()).isEqualTo("ab_miss");
    }
}
