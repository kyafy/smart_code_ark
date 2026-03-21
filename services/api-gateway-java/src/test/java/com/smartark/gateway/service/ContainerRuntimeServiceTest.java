package com.smartark.gateway.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerRuntimeServiceTest {

    @Mock
    private DockerClient dockerClient;
    @Mock
    private InspectContainerCmd inspectCmd;
    @Mock
    private StopContainerCmd stopCmd;
    @Mock
    private RemoveContainerCmd removeCmd;

    private ContainerRuntimeService containerRuntimeService;

    @BeforeEach
    void setUp() {
        containerRuntimeService = new ContainerRuntimeService(dockerClient);
        ReflectionTestUtils.setField(containerRuntimeService, "baseImage", "node:20-alpine");
        ReflectionTestUtils.setField(containerRuntimeService, "containerMemoryMb", 512L);
        ReflectionTestUtils.setField(containerRuntimeService, "containerCpus", 1.0);
        ReflectionTestUtils.setField(containerRuntimeService, "containerPort", 5173);
        ReflectionTestUtils.setField(containerRuntimeService, "hostPortRangeStart", 30000);
        ReflectionTestUtils.setField(containerRuntimeService, "hostPortRangeEnd", 31000);
    }

    private InspectContainerResponse mockInspectResponse(boolean running) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(response.getState()).thenReturn(state);
        return response;
    }

    // ===== stopAndRemoveContainer Tests =====

    @Test
    void stopAndRemoveContainer_shouldStopAndRemoveRunningContainer() {
        InspectContainerResponse response = mockInspectResponse(true);

        when(dockerClient.inspectContainerCmd("c1")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(response);
        when(dockerClient.stopContainerCmd("c1")).thenReturn(stopCmd);
        when(stopCmd.withTimeout(10)).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd("c1")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        containerRuntimeService.stopAndRemoveContainer("c1");

        verify(stopCmd).exec();
        verify(removeCmd).exec();
    }

    @Test
    void stopAndRemoveContainer_shouldSkipStopForNonRunningContainer() {
        InspectContainerResponse response = mockInspectResponse(false);

        when(dockerClient.inspectContainerCmd("c1b")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(response);
        when(dockerClient.removeContainerCmd("c1b")).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        containerRuntimeService.stopAndRemoveContainer("c1b");

        verify(dockerClient, never()).stopContainerCmd(any());
        verify(removeCmd).exec();
    }

    @Test
    void stopAndRemoveContainer_shouldHandleNotFoundException() {
        when(dockerClient.inspectContainerCmd("c2")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("not found"));

        // Should not throw
        containerRuntimeService.stopAndRemoveContainer("c2");

        verify(dockerClient, never()).stopContainerCmd(any());
    }

    @Test
    void stopAndRemoveContainer_shouldDoNothingForNullId() {
        containerRuntimeService.stopAndRemoveContainer(null);
        containerRuntimeService.stopAndRemoveContainer("");
        containerRuntimeService.stopAndRemoveContainer("   ");

        verify(dockerClient, never()).inspectContainerCmd(any());
    }

    // ===== isContainerRunning Tests =====

    @Test
    void isContainerRunning_shouldReturnTrueForRunningContainer() {
        InspectContainerResponse response = mockInspectResponse(true);

        when(dockerClient.inspectContainerCmd("c3")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(response);

        assertThat(containerRuntimeService.isContainerRunning("c3")).isTrue();
    }

    @Test
    void isContainerRunning_shouldReturnFalseForStoppedContainer() {
        InspectContainerResponse response = mockInspectResponse(false);

        when(dockerClient.inspectContainerCmd("c3b")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(response);

        assertThat(containerRuntimeService.isContainerRunning("c3b")).isFalse();
    }

    @Test
    void isContainerRunning_shouldReturnFalseForNullId() {
        assertThat(containerRuntimeService.isContainerRunning(null)).isFalse();
        assertThat(containerRuntimeService.isContainerRunning("")).isFalse();
    }

    @Test
    void isContainerRunning_shouldReturnFalseWhenNotFound() {
        when(dockerClient.inspectContainerCmd("c4")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("gone"));

        assertThat(containerRuntimeService.isContainerRunning("c4")).isFalse();
    }

    // ===== ExecResult Tests =====

    @Test
    void execResult_isSuccess_shouldReturnTrueForZeroExitCode() {
        ContainerRuntimeService.ExecResult success = new ContainerRuntimeService.ExecResult(0, "done");
        assertThat(success.isSuccess()).isTrue();
        assertThat(success.exitCode()).isEqualTo(0);
        assertThat(success.output()).isEqualTo("done");
    }

    @Test
    void execResult_isSuccess_shouldReturnFalseForNonZeroExitCode() {
        ContainerRuntimeService.ExecResult failure = new ContainerRuntimeService.ExecResult(1, "error");
        assertThat(failure.isSuccess()).isFalse();
    }

    @Test
    void execResult_isSuccess_shouldReturnFalseForNegativeExitCode() {
        ContainerRuntimeService.ExecResult interrupted = new ContainerRuntimeService.ExecResult(-1, "interrupted");
        assertThat(interrupted.isSuccess()).isFalse();
    }

    // ===== Health Check Tests =====

    @Test
    void checkHealth_shouldReturnFalseOnImmediateTimeout() {
        boolean result = containerRuntimeService.checkHealth("localhost", 99999, 0, 100);
        assertThat(result).isFalse();
    }

    // ===== getContainerLogs Tests =====

    @Test
    void getContainerLogs_shouldReturnEmptyForNullId() {
        assertThat(containerRuntimeService.getContainerLogs(null, 100)).isEmpty();
        assertThat(containerRuntimeService.getContainerLogs("", 100)).isEmpty();
    }

    @Test
    void getContainerLogs_shouldReturnErrorMessageWhenNotFound() {
        when(dockerClient.logContainerCmd("c5")).thenThrow(new NotFoundException("gone"));

        String logs = containerRuntimeService.getContainerLogs("c5", 100);
        assertThat(logs).contains("Container not found");
    }
}
