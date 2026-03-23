package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RagIndexEnrichStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(RagIndexEnrichStep.class);
    private final RagService ragService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperSourceRepository paperSourceRepository;

    public RagIndexEnrichStep(RagService ragService,
                              PaperTopicSessionRepository paperTopicSessionRepository,
                              PaperSourceRepository paperSourceRepository) {
        this.ragService = ragService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperSourceRepository = paperSourceRepository;
    }

    @Override
    public String getStepCode() {
        return "rag_index_enrich";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        logger.info("Step rag_index_enrich start: taskId={}, sessionId={}, sourceCount={}",
                context.getTask().getId(), session.getId(), sources.size());

        RagService.RagIndexResult result = ragService.indexSources(
                session.getId(), sources, session.getDiscipline());
        logger.info("Step rag_index_enrich output: taskId={}, sessionId={}, indexedDocs={}, indexedChunks={}",
                context.getTask().getId(), session.getId(), result.docCount(), result.chunkCount());

        context.setRagIndexedChunkCount(result.chunkCount());

        session.setStatus("rag_indexed");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }
}
