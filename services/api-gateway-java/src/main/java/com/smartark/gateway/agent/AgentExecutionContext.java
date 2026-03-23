package com.smartark.gateway.agent;

import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.agent.model.PaperSourceItem;
import com.smartark.gateway.agent.model.RagEvidenceItem;
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
    private Long paperSessionId;
    private String topic;
    private String discipline;
    private String degreeLevel;
    private String methodPreference;
    private List<PaperSourceItem> retrievedSources;
    private String outlineDraftJson;
    private String expandedOutlineJson;
    private String manuscriptJson;
    private String qualityReportJson;
    private List<String> qualityIssues;
    private int ragIndexedChunkCount;
    private List<RagEvidenceItem> ragEvidenceItems;
    private String chapterEvidenceMapJson;
    private List<String> contractViolations;

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

    public Long getPaperSessionId() {
        return paperSessionId;
    }

    public void setPaperSessionId(Long paperSessionId) {
        this.paperSessionId = paperSessionId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDiscipline() {
        return discipline;
    }

    public void setDiscipline(String discipline) {
        this.discipline = discipline;
    }

    public String getDegreeLevel() {
        return degreeLevel;
    }

    public void setDegreeLevel(String degreeLevel) {
        this.degreeLevel = degreeLevel;
    }

    public String getMethodPreference() {
        return methodPreference;
    }

    public void setMethodPreference(String methodPreference) {
        this.methodPreference = methodPreference;
    }

    public List<PaperSourceItem> getRetrievedSources() {
        return retrievedSources;
    }

    public void setRetrievedSources(List<PaperSourceItem> retrievedSources) {
        this.retrievedSources = retrievedSources;
    }

    public String getOutlineDraftJson() {
        return outlineDraftJson;
    }

    public void setOutlineDraftJson(String outlineDraftJson) {
        this.outlineDraftJson = outlineDraftJson;
    }

    public String getQualityReportJson() {
        return qualityReportJson;
    }

    public void setQualityReportJson(String qualityReportJson) {
        this.qualityReportJson = qualityReportJson;
    }

    public String getExpandedOutlineJson() {
        return expandedOutlineJson;
    }

    public void setExpandedOutlineJson(String expandedOutlineJson) {
        this.expandedOutlineJson = expandedOutlineJson;
    }

    public String getManuscriptJson() {
        return manuscriptJson;
    }

    public void setManuscriptJson(String manuscriptJson) {
        this.manuscriptJson = manuscriptJson;
    }

    public List<String> getQualityIssues() {
        return qualityIssues;
    }

    public void setQualityIssues(List<String> qualityIssues) {
        this.qualityIssues = qualityIssues;
    }

    public int getRagIndexedChunkCount() {
        return ragIndexedChunkCount;
    }

    public void setRagIndexedChunkCount(int ragIndexedChunkCount) {
        this.ragIndexedChunkCount = ragIndexedChunkCount;
    }

    public List<RagEvidenceItem> getRagEvidenceItems() {
        return ragEvidenceItems;
    }

    public void setRagEvidenceItems(List<RagEvidenceItem> ragEvidenceItems) {
        this.ragEvidenceItems = ragEvidenceItems;
    }

    public String getChapterEvidenceMapJson() {
        return chapterEvidenceMapJson;
    }

    public void setChapterEvidenceMapJson(String chapterEvidenceMapJson) {
        this.chapterEvidenceMapJson = chapterEvidenceMapJson;
    }

    public List<String> getContractViolations() {
        return contractViolations;
    }

    public void setContractViolations(List<String> contractViolations) {
        this.contractViolations = contractViolations;
    }
}
