package com.smartark.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ArxivService;
import com.smartark.gateway.service.CrossrefService;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.QualityGateService;
import com.smartark.gateway.service.RagService;
import com.smartark.gateway.service.SemanticScholarService;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalCapabilityControllerTest {

    @Mock
    private ModelService modelService;
    @Mock
    private TemplateRepoService templateRepoService;
    @Mock
    private SemanticScholarService semanticScholarService;
    @Mock
    private CrossrefService crossrefService;
    @Mock
    private ArxivService arxivService;
    @Mock
    private RagService ragService;
    @Mock
    private QualityGateService qualityGateService;
    @Mock
    private PaperSourceRepository paperSourceRepository;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        InternalCapabilityController controller = new InternalCapabilityController(
                modelService,
                templateRepoService,
                semanticScholarService,
                crossrefService,
                arxivService,
                ragService,
                qualityGateService,
                paperSourceRepository,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(controller, "internalToken", "test-token");
        ReflectionTestUtils.setField(controller, "workspaceRoot", "/tmp/smartark");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void modelStructure_shouldReturnFiles() throws Exception {
        when(modelService.generateProjectStructure("prd", "springboot", "vue3", "mysql", ""))
                .thenReturn(List.of("README.md", "backend/pom.xml"));

        mockMvc.perform(post("/api/internal/model/structure")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prd": "prd",
                                  "stack": {
                                    "backend": "springboot",
                                    "frontend": "vue3",
                                    "db": "mysql"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0]").value("README.md"));
    }

    @Test
    void templateResolve_shouldReturnResolvedTemplate() throws Exception {
        TemplateRepoService.TemplateMetadata metadata = new TemplateRepoService.TemplateMetadata(
                "nextjs-mysql",
                "Next + MySQL",
                "web",
                "desc",
                Map.of("frontend", "frontend"),
                Map.of("backend", "springboot"),
                Map.of("start", "pnpm dev"),
                Map.of("frontendPage", "app/page.tsx")
        );
        TemplateRepoService.TemplateSelection selection = new TemplateRepoService.TemplateSelection(
                "nextjs-mysql",
                Paths.get("template-repo/templates/nextjs-mysql"),
                "backend",
                "frontend",
                metadata
        );
        when(templateRepoService.resolveTemplate("nextjs-mysql", "springboot", "nextjs", "mysql"))
                .thenReturn(Optional.of(selection));

        mockMvc.perform(post("/api/internal/template/resolve")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "template_id": "nextjs-mysql",
                                  "stack": {
                                    "backend": "springboot",
                                    "frontend": "nextjs",
                                    "db": "mysql"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template_key").value("nextjs-mysql"))
                .andExpect(jsonPath("$.metadata.name").value("Next + MySQL"));
    }

    @Test
    void ragIndex_shouldPersistSourcesAndReturnChunkCount() throws Exception {
        when(ragService.indexSources(any(), any(), any()))
                .thenReturn(new RagService.RagIndexResult(12, 3));

        mockMvc.perform(post("/api/internal/rag/index")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "session_id": 101,
                                  "discipline": "computer science",
                                  "sources": [
                                    {
                                      "paperId": "p1",
                                      "title": "Title 1",
                                      "authors": ["A1"],
                                      "abstractText": "abstract"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunk_count").value(12))
                .andExpect(jsonPath("$.doc_count").value(3));

        verify(paperSourceRepository).deleteBySessionId(101L);
        verify(paperSourceRepository).saveAll(any());
    }

    @Test
    void qualityEvaluate_shouldUseTaskWorkspaceByDefault() throws Exception {
        when(qualityGateService.evaluate(any()))
                .thenReturn(new QualityGateService.QualityGateResult(
                        true,
                        1.0,
                        List.of(),
                        "2026-04-03T10:00:00"
                ));

        mockMvc.perform(post("/api/internal/quality/evaluate")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_id": "task-qa-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(1.0));

        verify(qualityGateService).persistReport(any(), any());
    }

    @Test
    void persistOutline_shouldCreateNewVersionAndUpdateSession() throws Exception {
        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(11L);
        session.setTaskId("task-paper-1");
        session.setStatus("retrieved");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        when(paperTopicSessionRepository.findById(11L)).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(11L))
                .thenReturn(Optional.empty());
        when(paperOutlineVersionRepository.save(any(PaperOutlineVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/internal/paper/11/outline")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "citation_style": "GB/T 7714",
                                  "outline_json": {
                                    "chapters": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(11))
                .andExpect(jsonPath("$.version_no").value(1));

        ArgumentCaptor<PaperOutlineVersionEntity> captor = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getOutlineJson()).contains("chapters");
        verify(paperTopicSessionRepository).save(any(PaperTopicSessionEntity.class));
    }

    @Test
    void endpoint_shouldRejectInvalidToken() throws Exception {
        mockMvc.perform(post("/api/internal/model/structure")
                        .header("X-Internal-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prd", "test"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid internal token"));

        verify(modelService, never()).generateProjectStructure(any(), any(), any(), any(), any());
    }
}
