package com.smartark.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload for creating a paper-generation task from a topic.")
public record PaperOutlineGenerateRequest(
        @NotBlank
        @Schema(description = "Paper topic to be researched.", example = "基于多智能体协作的软件工程代码生成质量优化研究")
        String topic,
        @NotBlank
        @Schema(description = "Primary discipline.", example = "软件工程")
        String discipline,
        @NotBlank
        @Schema(description = "Degree level.", example = "硕士")
        String degreeLevel,
        @Schema(description = "Optional method preference.", example = "实验研究")
        String methodPreference
) {
}
