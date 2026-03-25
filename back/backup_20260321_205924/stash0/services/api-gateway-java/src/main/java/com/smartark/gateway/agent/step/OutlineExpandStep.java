package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class OutlineExpandStep implements AgentStep {
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final ObjectMapper objectMapper;

    public OutlineExpandStep(ModelService modelService,
                             PaperTopicSessionRepository paperTopicSessionRepository,
                             PaperOutlineVersionRepository paperOutlineVersionRepository,
                             PaperSourceRepository paperSourceRepository,
                             ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "outline_expand";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow();
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .filter(s -> s.getPaperId() != null && !s.getPaperId().startsWith("NO_RESULT_"))
                .filter(s -> !"degraded".equalsIgnoreCase(s.getSectionKey()))
                .toList();

        JsonNode expanded = modelService.expandPaperOutline(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                session.getDiscipline(),
                session.getDegreeLevel(),
                session.getMethodPreference(),
                session.getResearchQuestionsJson(),
                objectMapper.readTree(version.getOutlineJson()),
                objectMapper.valueToTree(sources)
        );
        if (!isExpandedSchemaValid(expanded)) {
            expanded = modelService.expandPaperOutline(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    session.getTopic(),
                    session.getTopicRefined(),
                    session.getDiscipline(),
                    session.getDegreeLevel(),
                    session.getMethodPreference(),
                    session.getResearchQuestionsJson(),
                    objectMapper.readTree(version.getOutlineJson()),
                    objectMapper.valueToTree(sources)
            );
        }
        ObjectNode normalized = normalizeExpanded(expanded, session);

        version.setManuscriptJson(objectMapper.writeValueAsString(normalized));
        paperOutlineVersionRepository.save(version);

        context.setExpandedOutlineJson(objectMapper.writeValueAsString(normalized.path("chapters")));
        context.setManuscriptJson(version.getManuscriptJson());
        session.setStatus("expanded");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private boolean isExpandedSchemaValid(JsonNode root) {
        if (root == null || !root.path("chapters").isArray()) {
            return false;
        }
        JsonNode chapters = root.path("chapters");
        if (chapters.isEmpty()) {
            return false;
        }
        for (JsonNode chapter : chapters) {
            if (chapter.path("title").asText("").isBlank()) {
                return false;
            }
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                return false;
            }
            for (JsonNode section : sections) {
                if (section.path("title").asText("").isBlank()) return false;
                if (section.path("coreArgument").asText("").isBlank()) return false;
                if (section.path("method").asText("").isBlank()) return false;
                if (section.path("dataPlan").asText("").isBlank()) return false;
                if (section.path("expectedResult").asText("").isBlank()) return false;
                if (!section.path("citations").isArray() || section.path("citations").isEmpty()) return false;
            }
        }
        return true;
    }

    private ObjectNode normalizeExpanded(JsonNode expanded, PaperTopicSessionEntity session) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("topic", session.getTopic() == null ? "" : session.getTopic());
        root.put("topicRefined", session.getTopicRefined() == null ? "" : session.getTopicRefined());
        root.set("researchQuestions", parseQuestions(session.getResearchQuestionsJson()));

        ArrayNode normalizedChapters = objectMapper.createArrayNode();
        JsonNode inputChapters = expanded == null ? objectMapper.createArrayNode() : expanded.path("chapters");
        if (!inputChapters.isArray()) {
            inputChapters = objectMapper.createArrayNode();
        }
        int chapterIndex = 1;
        for (JsonNode chapter : inputChapters) {
            ObjectNode ch = objectMapper.createObjectNode();
            ch.put("index", chapter.path("index").asInt(chapterIndex));
            ch.put("title", fallback(chapter.path("title").asText(""), "第" + chapterIndex + "章"));
            ch.put("summary", fallback(chapter.path("summary").asText(""), "本章围绕研究问题展开背景、方法与结果分析。"));
            ch.put("objective", fallback(chapter.path("objective").asText(""), "明确本章研究目标与论证路径。"));

            ArrayNode normalizedSections = objectMapper.createArrayNode();
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                ObjectNode sec = objectMapper.createObjectNode();
                sec.put("title", "核心内容");
                sec.put("coreArgument", "围绕研究问题形成可验证论点。");
                sec.put("method", "采用文献分析与对比研究方法。");
                sec.put("dataPlan", "结合公开数据与相关研究结论进行论证。");
                sec.put("expectedResult", "形成可复核的章节结论。");
                ArrayNode citations = objectMapper.createArrayNode();
                citations.add("[待补充引用]");
                sec.set("citations", citations);
                normalizedSections.add(sec);
            } else {
                for (JsonNode section : sections) {
                    ObjectNode sec = objectMapper.createObjectNode();
                    sec.put("title", fallback(section.path("title").asText(""), "核心内容"));
                    sec.put("coreArgument", fallback(section.path("coreArgument").asText(""), fallback(section.path("content").asText(""), "围绕研究问题形成可验证论点。")));
                    sec.put("method", fallback(section.path("method").asText(""), "采用文献分析与对比研究方法。"));
                    sec.put("dataPlan", fallback(section.path("dataPlan").asText(""), "结合公开数据与相关研究结论进行论证。"));
                    sec.put("expectedResult", fallback(section.path("expectedResult").asText(""), "形成可复核的章节结论。"));
                    ArrayNode citations = objectMapper.createArrayNode();
                    JsonNode src = section.path("citations");
                    if (src.isArray() && !src.isEmpty()) {
                        src.forEach(n -> {
                            String v = n.asText("");
                            if (!v.isBlank() && !v.startsWith("NO_RESULT_")) citations.add(v);
                        });
                    }
                    if (citations.isEmpty()) {
                        citations.add("[待补充引用]");
                    }
                    sec.set("citations", citations);
                    normalizedSections.add(sec);
                }
            }
            ch.set("sections", normalizedSections);
            normalizedChapters.add(ch);
            chapterIndex++;
        }
        root.set("chapters", normalizedChapters);
        return root;
    }

    private ArrayNode parseQuestions(String researchQuestionsJson) {
        if (researchQuestionsJson == null || researchQuestionsJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode n = objectMapper.readTree(researchQuestionsJson);
            if (n.isArray()) {
                ArrayNode out = objectMapper.createArrayNode();
                List<String> uniq = new ArrayList<>();
                n.forEach(item -> {
                    String v = item.asText("");
                    if (!v.isBlank() && !uniq.contains(v)) {
                        uniq.add(v);
                        out.add(v);
                    }
                });
                return out;
            }
            return objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private String fallback(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }
}
