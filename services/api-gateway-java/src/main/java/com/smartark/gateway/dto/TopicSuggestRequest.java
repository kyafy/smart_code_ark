package com.smartark.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload for topic suggestion rounds.")
public record TopicSuggestRequest(
        @Schema(description = "Existing topic session ID. Omit on the first round.", example = "123")
        Long sessionId,
        @NotBlank
        @Schema(description = "Research direction or thesis domain.", example = "多智能体系统与代码生成")
        String direction,
        @Schema(description = "Optional constraints such as data source, time budget, or target scenario.", example = "优先选择可公开复现的数据集，强调工程可落地性")
        String constraints,
        @Schema(description = "Suggestion round number.", example = "1")
        Integer round
) {
}
