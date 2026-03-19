package com.smartark.gateway.agent;

import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import java.nio.file.Path;
import java.util.List;

public class AgentExecutionContext {
    private TaskEntity task;
    private ProjectEntity project;
    private ProjectSpecEntity spec;
    private String instructions;
    private String normalizedInstructions;
    private Path workspaceDir;
    private List<String> fileList;
    private List<FilePlanItem> filePlan;

    public TaskEntity getTask() {
        return task;
    }

    public void setTask(TaskEntity task) {
        this.task = task;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public ProjectSpecEntity getSpec() {
        return spec;
    }

    public void setSpec(ProjectSpecEntity spec) {
        this.spec = spec;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getNormalizedInstructions() {
        return normalizedInstructions;
    }

    public void setNormalizedInstructions(String normalizedInstructions) {
        this.normalizedInstructions = normalizedInstructions;
    }

    public Path getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(Path workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public List<String> getFileList() {
        return fileList;
    }

    public void setFileList(List<String> fileList) {
        this.fileList = fileList;
    }

    public List<FilePlanItem> getFilePlan() {
        return filePlan;
    }

    public void setFilePlan(List<FilePlanItem> filePlan) {
        this.filePlan = filePlan;
    }
}
