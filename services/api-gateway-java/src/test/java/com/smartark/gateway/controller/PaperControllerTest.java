package com.smartark.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.exception.GlobalExceptionHandler;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.dto.PaperTraceabilityResult;
import com.smartark.gateway.dto.TopicSuggestResult;
import com.smartark.gateway.service.RagService;
import com.smartark.gateway.service.TaskService;
import com.smartark.gateway.service.TopicSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaperControllerTest {

    @Mock
    private TaskService taskService;
    @Mock
    private RagService ragService;
    @Mock
    private TopicSuggestionService topicSuggestionService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PaperController controller = new PaperController(taskService, ragService, topicSuggestionService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void generateOutline_success() throws Exception {
        when(taskService.generatePaperOutline(any())).thenReturn(new PaperOutlineGenerateResult("task-paper-1", "queued"));

        mvc.perform(post("/api/paper/outline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topic":"AI in education",
                                  "discipline":"Computer Science",
                                  "degreeLevel":"Undergraduate",
                                  "methodPreference":"experiment"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-paper-1"))
                .andExpect(jsonPath("$.data.status").value("queued"));
    }

    @Test
    void getOutline_success() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PaperOutlineResult result = new PaperOutlineResult(
                "task-paper-2",
                "GB/T 7714",
                "original",
                "refined",
                java.util.List.of("question1"),
                mapper.readTree("[{\"title\":\"Chapter 1\"}]"),
                mapper.readTree("{\"chapters\":[{\"title\":\"Chapter 1\"}] }"),
                mapper.readTree("{\"score\":90}"),
                mapper.readTree("[{\"title\":\"Ref\"}]"),
                java.math.BigDecimal.valueOf(90),
                1
        );
        when(taskService.getPaperOutline("task-paper-2")).thenReturn(result);

        mvc.perform(get("/api/paper/outline/task-paper-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-paper-2"))
                .andExpect(jsonPath("$.data.citationStyle").value("GB/T 7714"))
                .andExpect(jsonPath("$.data.chapters[0].title").value("Chapter 1"));
    }

    @Test
    void getManuscript_success() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PaperManuscriptResult result = new PaperManuscriptResult(
                "task-paper-3",
                "original",
                "refined",
                mapper.readTree("{\"chapters\":[{\"title\":\"Chapter 1\"}]}"),
                java.math.BigDecimal.valueOf(88.5),
                1
        );
        when(taskService.getPaperManuscript("task-paper-3")).thenReturn(result);

        mvc.perform(get("/api/paper/manuscript/task-paper-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-paper-3"))
                .andExpect(jsonPath("$.data.manuscript.chapters[0].title").value("Chapter 1"))
                .andExpect(jsonPath("$.data.rewriteRound").value(1));
    }

    @Test
    void getTraceability_success() throws Exception {
        PaperTraceabilityResult traceabilityResult = new PaperTraceabilityResult(
                "task-t-1",
                List.of(new PaperTraceabilityResult.ChapterEvidence("Chapter 1", 0, List.of(1, 2))),
                List.of(new PaperTraceabilityResult.EvidenceItem(1, "chunk-1", "doc-1", "crossref:1", "title", "content", "url", 2022, "crossref", 0.8, 0.9, "abstract")),
                12
        );
        when(taskService.getPaperTraceability("task-t-1")).thenReturn(traceabilityResult);

        mvc.perform(get("/api/paper/traceability/task-t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-t-1"))
                .andExpect(jsonPath("$.data.chapters[0].citationIndices[0]").value(1));
    }

    @Test
    void suggestTopics_success() throws Exception {
        TopicSuggestResult suggestResult = new TopicSuggestResult(
                100L,
                1,
                List.of(new TopicSuggestResult.SuggestedTopic("Title", List.of("Q1", "Q2"), "Why", List.of("k1", "k2")))
        );
        when(topicSuggestionService.suggest(any(), any())).thenReturn(suggestResult);

        mvc.perform(post("/api/paper/topic/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "direction":"AI in education",
                                  "constraints":"undergraduate"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.round").value(1));
    }

    @Test
    void getOutline_notFound() throws Exception {
        when(taskService.getPaperOutline("missing"))
                .thenThrow(new BusinessException(ErrorCodes.NOT_FOUND, "not found"));

        mvc.perform(get("/api/paper/outline/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND))
                .andExpect(jsonPath("$.message").value("not found"));
    }
}
