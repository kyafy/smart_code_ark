package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class PackageStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(PackageStep.class);
    
    private final ArtifactRepository artifactRepository;

    public PackageStep(ArtifactRepository artifactRepository) {
        this.artifactRepository = artifactRepository;
    }

    @Override
    public String getStepCode() {
        return "package";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Packaging artifacts...");
        String zipPath = packageArtifacts(context);
        saveArtifact(context.getTask(), zipPath);
    }

    private String packageArtifacts(AgentExecutionContext context) throws IOException {
        Path sourceDir = context.getWorkspaceDir();
        Path zipPath = sourceDir.getParent().resolve(context.getTask().getId() + ".zip");
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            Files.walk(sourceDir)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString());
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        logger.error("Error adding file to zip", e);
                    }
                });
        }
        return "file://" + zipPath.toString();
    }

    private void saveArtifact(TaskEntity task, String storageUrl) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setTaskId(task.getId());
        artifact.setProjectId(task.getProjectId());
        artifact.setArtifactType("zip");
        artifact.setStorageUrl(storageUrl);
        artifact.setSizeBytes(new File(storageUrl.substring(7)).length());
        artifact.setCreatedAt(LocalDateTime.now());
        artifactRepository.save(artifact);
    }
}
