package com.smartark.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.exception.GlobalExceptionHandler;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PaperController controller = new PaperController(taskService);
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
                                  "topic":"多智能体协同",
                                  "discipline":"计算机科学",
                                  "degreeLevel":"本科",
                                  "methodPreference":"实验法"
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
                "原始题目",
                "细化题目",
                java.util.List.of("问题1"),
                mapper.readTree("[{\"title\":\"第一章\"}]"),
                mapper.readTree("{\"chapters\":[{\"title\":\"第一章\"}]}"),
                mapper.readTree("{\"score\":90}"),
                mapper.readTree("[{\"title\":\"参考文献1\"}]"),
                java.math.BigDecimal.valueOf(90),
                1
        );
        when(taskService.getPaperOutline("task-paper-2")).thenReturn(result);

        mvc.perform(get("/api/paper/outline/task-paper-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-paper-2"))
                .andExpect(jsonPath("$.data.citationStyle").value("GB/T 7714"))
                .andExpect(jsonPath("$.data.chapters[0].title").value("第一章"));
    }

    @Test
    void getManuscript_success() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PaperManuscriptResult result = new PaperManuscriptResult(
                "task-paper-3",
                "原始题目",
                "细化题目",
                mapper.readTree("{\"chapters\":[{\"title\":\"第一章\"}]}"),
                java.math.BigDecimal.valueOf(88.5),
                1
        );
        when(taskService.getPaperManuscript("task-paper-3")).thenReturn(result);

        mvc.perform(get("/api/paper/manuscript/task-paper-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-paper-3"))
                .andExpect(jsonPath("$.data.manuscript.chapters[0].title").value("第一章"))
                .andExpect(jsonPath("$.data.rewriteRound").value(1));
    }

    @Test
    void getOutline_notFound() throws Exception {
        when(taskService.getPaperOutline("missing"))
                .thenThrow(new BusinessException(ErrorCodes.NOT_FOUND, "论文大纲尚未生成"));

        mvc.perform(get("/api/paper/outline/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND))
                .andExpect(jsonPath("$.message").value("论文大纲尚未生成"));
    }
}
