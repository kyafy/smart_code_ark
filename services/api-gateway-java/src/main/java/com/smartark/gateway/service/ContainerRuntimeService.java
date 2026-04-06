package com.smartark.gateway.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "smartark.preview.enabled", havingValue = "true")
public class ContainerRuntimeService {
    private static final Logger logger = LoggerFactory.getLogger(ContainerRuntimeService.class);

    private final DockerClient dockerClient;

    @Value("${smartark.preview.base-image:node:20-alpine}")
    private String baseImage;

    @Value("${smartark.preview.container-memory-mb:512}")
    private long containerMemoryMb;

    @Value("${smartark.preview.container-cpus:1.0}")
    private double containerCpus;

    @Value("${smartark.preview.container-port:5173}")
    private int containerPort;

    @Value("${smartark.preview.host-port-range-start:30000}")
    private int hostPortRangeStart;

    @Value("${smartark.preview.host-port-range-end:31000}")
    private int hostPortRangeEnd;

    public ContainerRuntimeService(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * Create and start a container for the given workspace.
     *
     * @param workspacePath absolute path to the frontend project directory
     * @param hostPort      the host port to bind
     * @param taskId        task identifier for naming
     * @return container ID
     */
    public String createAndStartContainer(String workspacePath, int hostPort, String taskId) {
        ExposedPort exposedPort = ExposedPort.tcp(containerPort);
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindPort(hostPort));

        long memoryBytes = containerMemoryMb * 1024 * 1024;
        long nanoCpus = (long) (containerCpus * 1_000_000_000L);

        String networkMode = "bridge";
        try {
            dockerClient.inspectNetworkCmd().withNetworkId("smart_code_ark_default").exec();
            networkMode = "smart_code_ark_default";
        } catch (Exception e) {
            logger.info("Network smart_code_ark_default not found, using bridge mode");
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withMemory(memoryBytes)
                .withNanoCPUs(nanoCpus)
                .withBinds(Bind.parse(workspacePath + ":/app"))
                .withNetworkMode(networkMode);

        CreateContainerResponse container = dockerClient.createContainerCmd(baseImage)
                .withName("smartark-preview-" + taskId)
                .withWorkingDir("/app")
                .withExposedPorts(exposedPort)
                .withHostConfig(hostConfig)
                .withCmd("tail", "-f", "/dev/null") // keep alive, we exec commands separately
                .exec();

        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();
        logger.info("Container started: id={}, taskId={}, hostPort={}", containerId, taskId, hostPort);
        return containerId;
    }

    /**
     * Execute a command inside a running container.
     *
     * @return ExecResult with exit code and combined output
     */
    public ExecResult execInContainer(String containerId, String... command) {
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd(command)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            dockerClient.execStartCmd(execCreate.getId())
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, "Interrupted: " + e.getMessage());
        }

        Long exitCode = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
        String output = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
        return new ExecResult(exitCode == null ? -1 : exitCode.intValue(), output);
    }

    /**
     * Execute a command in the background (detached) inside a container.
     */
    public void execDetached(String containerId, String... command) {
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .withTty(false)
                .withCmd(command)
                .exec();

        dockerClient.execStartCmd(execCreate.getId())
                .withDetach(true)
                .exec(new ExecStartResultCallback())
                .onComplete();

        logger.info("Detached exec started in container {}: {}", containerId, String.join(" ", command));
    }

    /**
     * Poll HTTP endpoint until it returns 200, or timeout.
     */
    public boolean checkHealth(String host, int port, int timeoutSeconds, int intervalMs) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        String url = "http://" + host + ":" + port + "/";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 400) {
                    logger.info("Health check passed: {} -> {}", url, code);
                    return true;
                }
            } catch (IOException ignored) {
                // not ready yet
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("Health check timed out after {}s: {}", timeoutSeconds, url);
        return false;
    }

    /**
     * Stop and remove a container. Idempotent: ignores NotFoundException.
     */
    public void stopAndRemoveContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            InspectContainerResponse info = dockerClient.inspectContainerCmd(containerId).exec();
            Boolean running = info.getState().getRunning();
            if (Boolean.TRUE.equals(running)) {
                dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            }
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            logger.info("Container removed: {}", containerId);
        } catch (NotFoundException e) {
            logger.info("Container already removed: {}", containerId);
        } catch (DockerException e) {
            logger.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Find an available host port within the configured range.
     */
    public int findAvailablePort() {
        for (int port = hostPortRangeStart; port <= hostPortRangeEnd; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new RuntimeException("No available port in range " + hostPortRangeStart + "-" + hostPortRangeEnd);
    }

    /**
     * Get container logs (last N lines).
     */
    public String getContainerLogs(String containerId, int tail) {
        if (containerId == null || containerId.isBlank()) {
            return "";
        }
        try {
            List<String> lines = new ArrayList<>();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .withFollowStream(false)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            lines.add(new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing());
                        }
                    })
                    .awaitCompletion(10, TimeUnit.SECONDS);
            return String.join("\n", lines);
        } catch (NotFoundException e) {
            return "[Container not found: " + containerId + "]";
        } catch (Exception e) {
            logger.warn("Failed to get logs for container {}: {}", containerId, e.getMessage());
            return "[Error reading logs: " + e.getMessage() + "]";
        }
    }

    /**
     * Check if a container exists and is running.
     */
    public boolean isContainerRunning(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return false;
        }
        try {
            InspectContainerResponse info = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(info.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        }
    }

    private boolean isPortAvailable(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public record ExecResult(int exitCode, String output) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
