package com.smartark.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Request payload for adopting a suggested topic into a paper session.")
public record TopicAdoptRequest(
        @NotNull
        @Schema(description = "Topic suggestion session ID.", example = "123")
        Long sessionId,
        @Schema(description = "Index of selected suggestion in current round.", example = "0")
        int selectedIndex,
        @Schema(description = "Optional custom title to override selected topic.", example = "面向复杂任务的软件工程多智能体协作框架研究")
        String customTitle,
        @Schema(description = "Optional custom research questions.", example = "[\"多智能体协作在复杂代码生成中的关键瓶颈是什么？\",\"如何量化协作策略带来的质量收益？\"]")
        List<String> customQuestions
) {
}
