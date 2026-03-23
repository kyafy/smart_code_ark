package com.smartark.gateway.dto;

import java.util.List;

public record PaperTraceabilityResult(
        String taskId,
        List<ChapterEvidence> chapters,
        List<EvidenceItem> globalEvidenceList,
        int totalChunksSearched
) {
    public record ChapterEvidence(
            String chapterTitle,
            int chapterIndex,
            List<Integer> citationIndices
    ) {
    }

    public record EvidenceItem(
            int citationIndex,
            String chunkUid,
            String docUid,
            String paperId,
            String title,
            String content,
            String url,
            Integer year,
            String source,
            Double vectorScore,
            Double rerankScore,
            String chunkType
    ) {
    }
}

